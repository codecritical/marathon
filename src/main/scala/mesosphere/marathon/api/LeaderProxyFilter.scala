package mesosphere.marathon.api

import java.io.{IOException, InputStream, OutputStream}
import java.net._
import javax.inject.Named
import javax.net.ssl._
import javax.servlet._
import javax.servlet.http.{HttpServletRequest, HttpServletResponse}

import akka.Done
import com.google.inject.Inject
import mesosphere.chaos.http.HttpConf
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.io.IO
import mesosphere.marathon.{LeaderProxyConf, ModuleNames}
import org.apache.http.HttpStatus
import org.slf4j.LoggerFactory

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.util.Try
import scala.util.control.NonFatal

/**
  * Servlet filter that proxies requests to the leader if we are not the leader.
  */
class LeaderProxyFilter @Inject() (
    httpConf: HttpConf,
    electionService: ElectionService,
    @Named(ModuleNames.HOST_PORT) myHostPort: String,
    forwarder: RequestForwarder) extends Filter {
  //scalastyle:off null

  import LeaderProxyFilter._

  @SuppressWarnings(Array("EmptyMethod"))
  def init(filterConfig: FilterConfig): Unit = {}

  private[this] val scheme = if (httpConf.disableHttp()) "https" else "http"

  private[this] def buildUrl(leaderData: String, request: HttpServletRequest): URL = {
    buildUrl(leaderData, request.getRequestURI, Option(request.getQueryString))
  }

  private[this] def buildUrl(
    leaderData: String,
    requestURI: String = "",
    queryStringOpt: Option[String] = None): URL =
    {
      queryStringOpt match {
        case Some(queryString) => new URL(s"$scheme://$leaderData$requestURI?$queryString")
        case None => new URL(s"$scheme://$leaderData$requestURI")
      }
    }

  //TODO: fix style issue and enable this scalastyle check
  //scalastyle:off cyclomatic.complexity method.length
  @tailrec
  final def doFilter(
    rawRequest: ServletRequest,
    rawResponse: ServletResponse,
    chain: FilterChain) {

    def waitForConsistentLeadership(response: HttpServletResponse): Boolean = {
      //scalastyle:off magic.number
      var retries = 10
      //scalastyle:on
      var consistent = false
      do {
        val weAreLeader = electionService.isLeader
        val currentLeaderData = electionService.leaderHostPort

        if (weAreLeader || currentLeaderData.exists(_ != myHostPort)) {
          log.info("Leadership info is consistent again!")
          consistent = true
          retries = 0
        } else {

          // as long as we are not flagged as elected yet, the leadership transition is still
          // taking place and we hold back any requests.
          if (retries >= 0) {
            log.info(s"Waiting for consistent leadership state. Are we leader?: $weAreLeader, leader: $currentLeaderData")
            sleep()
          } else {
            log.error(
              s"inconsistent leadership state, refusing request for ourselves at $myHostPort. " +
                s"Are we leader?: $weAreLeader, leader: $currentLeaderData")
          }
        }

        retries -= 1
      } while (retries >= 0)
      consistent
    }

    (rawRequest, rawResponse) match {
      case (request: HttpServletRequest, response: HttpServletResponse) =>
        lazy val leaderDataOpt = electionService.leaderHostPort

        if (electionService.isLeader) {
          response.addHeader(LeaderProxyFilter.HEADER_MARATHON_LEADER, buildUrl(myHostPort).toString)
          chain.doFilter(request, response)
        } else if (leaderDataOpt.forall(_ == myHostPort)) { // either not leader or ourselves
          log.info(
            "Do not proxy to myself. Waiting for consistent leadership state. " +
              s"Are we leader?: false, leader: $leaderDataOpt")
          if (waitForConsistentLeadership(response)) {
            doFilter(rawRequest, rawResponse, chain)
          } else {
            response.sendError(HttpStatus.SC_SERVICE_UNAVAILABLE, ERROR_STATUS_NO_CURRENT_LEADER)
          }
        } else {
          leaderDataOpt.map { leaderData =>
            val url = buildUrl(leaderData, request)
            try {
              forwarder.forward(url, request, response)
              Done
            } catch {
              case NonFatal(e) =>
                throw new RuntimeException("while proxying", e)
            }
          }.getOrElse {
            throw new RuntimeException("While proxying, no leader was found")
          }
        }
      case _ =>
        throw new IllegalArgumentException(s"expected http request/response but got $rawRequest/$rawResponse")
    }
  }

  protected def sleep(): Unit = {
    //scalastyle:off magic.number
    Thread.sleep(250)
    //scalastyle:on
  }

  @SuppressWarnings(Array("EmptyMethod"))
  def destroy() {
    //NO-OP
  }
}

object LeaderProxyFilter {
  private val log = LoggerFactory.getLogger(getClass.getName)

  val HEADER_MARATHON_LEADER: String = "X-Marathon-Leader"
  val ERROR_STATUS_NO_CURRENT_LEADER: String = "Could not determine the current leader"
}

/**
  * Forwards a HttpServletRequest to an URL.
  */
trait RequestForwarder {
  def forward(url: URL, request: HttpServletRequest, response: HttpServletResponse): Unit
}

class JavaUrlConnectionRequestForwarder @Inject() (
  @Named(JavaUrlConnectionRequestForwarder.NAMED_LEADER_PROXY_SSL_CONTEXT) sslContext: SSLContext,
  leaderProxyConf: LeaderProxyConf,
  @Named(ModuleNames.HOST_PORT) myHostPort: String)
    extends RequestForwarder {

  import JavaUrlConnectionRequestForwarder._

  private[this] val viaValue: String = s"1.1 $myHostPort"

  private lazy val ignoreHostnameVerifier = new javax.net.ssl.HostnameVerifier {
    override def verify(hostname: String, sslSession: SSLSession): Boolean = true
  }

  override def forward(url: URL, request: HttpServletRequest, response: HttpServletResponse): Unit = {

    def hasProxyLoop: Boolean = {
      val viaOpt = Option(request.getHeaders(HEADER_VIA)).map(_.asScala.toVector)
      viaOpt.exists(_.contains(viaValue))
    }

    def createAndConfigureConnection(url: URL): HttpURLConnection = {
      val connection = url.openConnection() match {
        case httpsConnection: HttpsURLConnection =>
          httpsConnection.setSSLSocketFactory(sslContext.getSocketFactory)

          if (leaderProxyConf.leaderProxySSLIgnoreHostname()) {
            httpsConnection.setHostnameVerifier(ignoreHostnameVerifier)
          }

          httpsConnection
        case httpConnection: HttpURLConnection =>
          httpConnection
        case connection: URLConnection =>
          throw new scala.RuntimeException(s"unexpected connection type: ${connection.getClass}")
      }

      connection.setConnectTimeout(leaderProxyConf.leaderProxyConnectionTimeout())
      connection.setReadTimeout(leaderProxyConf.leaderProxyReadTimeout())
      connection.setInstanceFollowRedirects(false)

      connection
    }

    def copyRequestHeadersToConnection(leaderConnection: HttpURLConnection, request: HttpServletRequest): Unit = {
      // getHeaderNames() and getHeaders() are known to return null, see:
      //http://docs.oracle.com/javaee/6/api/javax/servlet/http/HttpServletRequest.html#getHeaders(java.lang.String)
      val names = Option(request.getHeaderNames).map(_.asScala).getOrElse(Nil)
      for {
        name <- names
        // Reverse proxies commonly filter these headers: connection, host.
        //
        // The connection header is removed since it may make sense to persist the connection
        // for further requests even if this single client will stop using it.
        //
        // The host header is used to choose the correct virtual host and should be set to the hostname
        // of the URL for HTTP 1.1. Thus we do not preserve it, even though Marathon does not care.
        if !name.equalsIgnoreCase("host") && !name.equalsIgnoreCase("connection")
        headerValues <- Option(request.getHeaders(name))
        headerValue <- headerValues.asScala
      } {
        log.debug(s"addRequestProperty $name: $headerValue")
        leaderConnection.addRequestProperty(name, headerValue)
      }

      leaderConnection.addRequestProperty(HEADER_VIA, viaValue)
      val forwardedFor = Seq(
        Option(request.getHeader(HEADER_FORWARDED_FOR)),
        Option(request.getRemoteAddr)
      ).flatten.mkString(",")
      leaderConnection.addRequestProperty(HEADER_FORWARDED_FOR, forwardedFor)
    }

    def copyRequestBodyToConnection(leaderConnection: HttpURLConnection, request: HttpServletRequest): Unit = {
      request.getMethod match {
        case "GET" | "HEAD" | "DELETE" =>
          leaderConnection.setDoOutput(false)
        case _ =>
          leaderConnection.setDoOutput(true)

          IO.using(request.getInputStream) { requestInput =>
            IO.using(leaderConnection.getOutputStream) { proxyOutputStream =>
              copy(request.getInputStream, proxyOutputStream)
            }
          }
      }
    }

    def copyRequestToConnection(leaderConnection: HttpURLConnection, request: HttpServletRequest): Unit = {
      leaderConnection.setRequestMethod(request.getMethod)
      copyRequestHeadersToConnection(leaderConnection, request)
      copyRequestBodyToConnection(leaderConnection, request)
    }

    def copyConnectionResponse(leaderConnection: HttpURLConnection, response: HttpServletResponse): Unit = {
      val status = leaderConnection.getResponseCode
      response.setStatus(status)

      Option(leaderConnection.getHeaderFields).foreach { _.asScala.foreach { case (name, values) =>
        (Option(name), Option(values)) match {
          case (Some(headerName), Some(headerValues)) =>
            headerValues.asScala.foreach { value =>
             response.addHeader(headerName, value)
            }
          case _ =>
        }
      }}
      response.addHeader(HEADER_VIA, viaValue)

      IO.using(response.getOutputStream) { output =>
        try {
          IO.using(leaderConnection.getInputStream) { connectionInput => copy(connectionInput, output) }
        } catch {
          case e: IOException =>
            log.debug("got exception response, this is maybe an error code", e)
            IO.using(leaderConnection.getErrorStream) { connectionError => copy(connectionError, output) }
        }
      }
    }

    log.info(s"Proxying request to ${request.getMethod} $url from $myHostPort")

    try {
      if (hasProxyLoop) {
        log.error("Prevent proxy cycle, rejecting request")
        response.sendError(HttpStatus.SC_BAD_GATEWAY, ERROR_STATUS_LOOP)
      } else {
        val leaderConnection: HttpURLConnection = createAndConfigureConnection(url)
        try {
          copyRequestToConnection(leaderConnection, request)
          copyConnectionResponse(leaderConnection, response)
        } catch {
          case connException: ConnectException =>
            response.sendError(HttpStatus.SC_BAD_GATEWAY, ERROR_STATUS_CONNECTION_REFUSED)
        } finally {
          Try(leaderConnection.getInputStream.close())
          Try(leaderConnection.getErrorStream.close())
        }
      }
    } finally {
      Try(request.getInputStream.close())
      Try(response.getOutputStream.close())
    }

  }

  def copy(nullableIn: InputStream, nullableOut: OutputStream): Unit = {
    try {
      for {
        in <- Option(nullableIn)
        out <- Option(nullableOut)
      } IO.transfer(in, out, close = false)
    } catch {
      case e: UnknownServiceException =>
        log.warn("unexpected unknown service exception", e)
    }
  }
}

object JavaUrlConnectionRequestForwarder {
  private val log = LoggerFactory.getLogger(getClass)

  /** Header for proxy loop detection. Simply "Via" is ignored by the URL connection.*/
  val HEADER_VIA: String = "X-Marathon-Via"
  val ERROR_STATUS_LOOP: String = "Detected proxying loop."
  val ERROR_STATUS_CONNECTION_REFUSED: String = "Connection to leader refused."

  val HEADER_FORWARDED_FOR: String = "X-Forwarded-For"
  final val NAMED_LEADER_PROXY_SSL_CONTEXT = "JavaUrlConnectionRequestForwarder.SSLContext"
}
