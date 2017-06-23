/* =========================================================================================
 * Copyright © 2013-2016 the kamon project <http://kamon.io/>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 * =========================================================================================
 */

package kamon.play

import javax.inject.Inject

import akka.stream.Materializer
import kamon.Kamon
import kamon.metric.instrument.CollectionContext
import kamon.play.action.TraceName
import kamon.trace.{MetricsOnlyContext, Status, TraceLocal, Tracer}
import org.scalatest.Inside
import org.scalatestplus.play._
import play.api.Application
import play.api.http.{HttpErrorHandler, HttpFilters, Writeable}
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.ws.{WSClient, WSRequest}
import play.api.mvc.Results.{InternalServerError, Ok}
import play.api.mvc.{Action, Handler, _}
import play.api.routing.{HandlerDef, SimpleRouter}
import play.api.test.Helpers._
import play.api.test._
import play.core.routing._
import javax.inject.Singleton

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class RequestInstrumentationSpec extends PlaySpec with OneServerPerSuite with Inside {
  System.setProperty("config.file", "./kamon-play-2.6.x/src/test/resources/conf/application.conf")

  override lazy val port: Port = 19002
  val executor = scala.concurrent.ExecutionContext.Implicits.global

  val kamonFilter = new GuiceApplicationBuilder()
    .injector()
    .instanceOf[KamonFilter]

  val withRoutes: PartialFunction[(String, String), Handler] = {
    case ("GET", "/async") ⇒
      Action.async {
        Future {
          Ok("Async.async")
        }(executor)
      }
    case ("GET", "/notFound") ⇒
      Action {
        Results.NotFound
      }
    case ("GET", "/error") ⇒
      Action {
        InternalServerError("This page will generate an error!")
      }
    case ("GET", "/redirect") ⇒
      Action {
        Results.Redirect("/redirected", MOVED_PERMANENTLY)
      }
    case ("GET", "/default") ⇒
      Action {
        Ok("default")
      }
    case ("GET", "/async-renamed") ⇒
      TraceName("renamed-trace") {
        Action.async {
          Future {
            Ok("Async.async")
          }(executor)
        }
      }(executor)
    case ("GET", "/retrieve") ⇒
      Action {
        Ok("retrieve from TraceLocal")
      }
  }

  val additionalConfiguration : Map[String, _] = Map(
    ("application.router", "kamon.play.Routes"),
    ("play.http.filters", "kamon.play.TestHttpFilters"),
    ("play.http.requestHandler", "play.api.http.DefaultHttpRequestHandler"),
    ("logger.root", "OFF"),
    ("logger.play", "OFF"),
    ("logger.application", "OFF"))

  implicit override lazy val app: Application = new GuiceApplicationBuilder()
    .configure(additionalConfiguration)
    .routes(withRoutes)
    .build

  val traceTokenValue = "kamon-trace-token-test"
  val traceTokenHeaderName = "X-Trace-Token"
  val expectedToken = Some(traceTokenValue)
  val traceTokenHeader = traceTokenHeaderName -> traceTokenValue
  val traceLocalStorageValue = "localStorageValue"
  val traceLocalStorageKey = "localStorageKey"
  val traceLocalStorageHeader = traceLocalStorageKey -> traceLocalStorageValue

  "the Request instrumentation" should {
    "respond to the Async Action with X-Trace-Token" in {
      val Some(result) = route(app, FakeRequest(GET, "/async").withHeaders(traceTokenHeader, traceLocalStorageHeader))
      header(traceTokenHeaderName, result) must be(expectedToken)
    }

    "respond to the NotFound Action with X-Trace-Token" in {
      val Some(result) = route(app, FakeRequest(GET, "/notFound").withHeaders(traceTokenHeader))
      header(traceTokenHeaderName, result) must be(expectedToken)
    }

    "respond to the Default Action with X-Trace-Token" in {
      val Some(result) = route(app, FakeRequest(GET, "/default").withHeaders(traceTokenHeader))
      header(traceTokenHeaderName, result) must be(expectedToken)
    }

    "respond to the Redirect Action with X-Trace-Token" in {
      val Some(result) = route(app, FakeRequest(GET, "/redirect").withHeaders(traceTokenHeader))
      header("Location", result) must be(Some("/redirected"))
      header(traceTokenHeaderName, result) must be(expectedToken)
    }

    "respond to the Async Action with X-Trace-Token and the renamed trace" in {
      val result = Await.result(route(app, FakeRequest(GET, "/async-renamed").withHeaders(traceTokenHeader)).get, 10 seconds)
      Tracer.currentContext.name must be("renamed-trace")
      Some(result.header.headers(traceTokenHeaderName)) must be(expectedToken)
    }

    "propagate the TraceContext and LocalStorage through of filters in the current request" in {
      route(app, FakeRequest(GET, "/retrieve").withHeaders(traceTokenHeader, traceLocalStorageHeader))
      TraceLocal.retrieve(TraceLocalKey).get must be(traceLocalStorageValue)
    }

    "propagate metadata generated in the async filters" in {
      route(app, FakeRequest(GET, "/retrieve"))
      Tracer.currentContext mustBe 'closed
      inside(Tracer.currentContext) {
        case ctx: MetricsOnlyContext =>
          ctx.tags must contain("filter" -> "async")
      }
    }

    "record http server metrics for all processed requests" in {
      val collectionContext = CollectionContext(100)
      Kamon.metrics.find("play-server", "http-server").get.collect(collectionContext)

      for (repetition ← 1 to 10) {
        Await.result(route(app, FakeRequest(GET, "/default").withHeaders(traceTokenHeader)).get, 10 seconds)
      }

      for (repetition ← 1 to 5) {
        Await.result(route(app, FakeRequest(GET, "/notFound").withHeaders(traceTokenHeader)).get, 10 seconds)
      }

      for (repetition ← 1 to 5) {
        Await.result(routeWithOnError(FakeRequest(GET, "/error").withHeaders(traceTokenHeader)).get, 10 seconds)
      }

      val snapshot = Kamon.metrics.find("play-server", "http-server").get.collect(collectionContext)
      snapshot.counter("GET: /default_200").get.count must be(10)
      snapshot.counter("GET: /notFound_404").get.count must be(5)
      snapshot.counter("GET: /error_500").get.count must be(5)
      snapshot.counter("200").get.count must be(10)
      snapshot.counter("404").get.count must be(5)
      snapshot.counter("500").get.count must be(5)
    }
  }

  def routeWithOnError[T](req: Request[T])(implicit w: Writeable[T]): Option[Future[Result]] = {
    val errorHandler = new GuiceApplicationBuilder()
      .injector()
      .instanceOf[ErrorHandler]
    route(app, req).map { result ⇒
      result.recoverWith {
        case t: Throwable ⇒ errorHandler.onServerError(req, t)
      }
    }
  }
}

object TraceLocalKey extends TraceLocal.TraceLocalKey[String]

class TraceLocalFilter @Inject() (implicit val mat: Materializer) extends Filter {

  val traceLocalStorageValue = "localStorageValue"
  val traceLocalStorageKey = "localStorageKey"
  val traceLocalStorageHeader = traceLocalStorageKey -> traceLocalStorageValue

  override def apply(next: (RequestHeader) ⇒ Future[Result])(header: RequestHeader): Future[Result] = {
    Tracer.withContext(Tracer.currentContext) {

      TraceLocal.store(TraceLocalKey)(header.headers.get(traceLocalStorageKey).getOrElse("unknown"))

      next(header).map {
        result ⇒
          {
            result.withHeaders(traceLocalStorageKey -> TraceLocal.retrieve(TraceLocalKey).get)
          }
      }
    }
  }
}

@Singleton
class ErrorHandler extends HttpErrorHandler {

  def onClientError(request: RequestHeader, statusCode: Int, message: String) = {
    Future.successful(
      new Results.Status(statusCode)("A client error occurred: " + message)
    )
  }

  def onServerError(request: RequestHeader, exception: Throwable) = {
    Future.successful(
      InternalServerError("A server error occurred: " + exception.getMessage)
    )
  }
}

class TraceAsyncFilter @Inject() extends EssentialFilter {
  override def apply(next: EssentialAction): EssentialAction = EssentialAction { requestHeader =>
    def onResult(result: Result): Result = {
      if (Tracer.currentContext.status == Status.Open)
        Tracer.currentContext.addTag("filter", "async")
      result
    }

    val nextAccumulator = next(requestHeader)
    nextAccumulator.map(onResult)
  }
}

class TestHttpFilters @Inject() (traceLocalFilter: TraceLocalFilter, traceAsyncFilter: TraceAsyncFilter) extends HttpFilters {
  val filters = Seq(traceLocalFilter, traceAsyncFilter)
}

class Routes @Inject() (application: controllers.Application) extends GeneratedRouter with SimpleRouter {
  val prefix = "/"

  lazy val defaultPrefix = {
    if (prefix.endsWith("/")) "" else "/"
  }

  // Gets
  private[this] lazy val Application_getRouted =
    Route("GET", PathPattern(List(StaticPart(prefix), StaticPart(defaultPrefix), StaticPart("getRouted"))))

  def routes: PartialFunction[RequestHeader, Handler] = {
    case Application_getRouted(params) ⇒ call {
      createInvoker(application.getRouted,
        HandlerDef(this.getClass.getClassLoader, "", "controllers.Application", "getRouted", Nil, "GET", """some comment""", prefix + """getRouted""")).call(application.getRouted)
    }
  }

  override def errorHandler: HttpErrorHandler = new HttpErrorHandler() {
    override def onClientError(request: RequestHeader, statusCode: Int, message: String): Future[Result] = Future.successful(Results.InternalServerError)
    override def onServerError(request: RequestHeader, exception: Throwable): Future[Result] = Future.successful(Results.InternalServerError)
  }
}

object controllers {
  import play.api.mvc._

  class Application extends Controller {
    val getRouted = Action {
      Ok("invoked getRouted")
    }
  }
}

class TestNameGenerator extends NameGenerator {
  import java.util.Locale

  import kamon.util.TriemapAtomicGetOrElseUpdate.Syntax
  import play.api.routing.Router

  import scala.collection.concurrent.TrieMap

  private val cache = TrieMap.empty[String, String]
  private val normalizePattern = """\$([^<]+)<[^>]+>""".r

  def generateTraceName(requestHeader: RequestHeader): String = requestHeader.attrs.get(Router.Attrs.HandlerDef).map { handlerDef ⇒
    cache.atomicGetOrElseUpdate(s"${handlerDef.verb}${handlerDef.path}", {
      val traceName = {
        // Convert paths of form GET /foo/bar/$paramname<regexp>/blah to foo.bar.paramname.blah.get
        val p = normalizePattern.replaceAllIn(handlerDef.path, "$1").replace('/', '.').dropWhile(_ == '.')
        val normalisedPath = {
          if (p.lastOption.exists(_ != '.')) s"$p."
          else p
        }
        s"$normalisedPath${handlerDef.verb.toLowerCase(Locale.ENGLISH)}"
      }
      traceName
    })
  } getOrElse s"${requestHeader.method}: ${requestHeader.uri}"

  def generateHttpClientSegmentName(request: WSRequest): String = request.url
}