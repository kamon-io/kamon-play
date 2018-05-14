/*
 * =========================================================================================
 * Copyright © 2013-2018 the kamon project <http://kamon.io/>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 * =========================================================================================
 */

package kamon.play.instrumentation.kanela


import java.util.concurrent.Callable

import kamon.play.instrumentation.RequestHandlerInstrumentation
import kamon.play.instrumentation.kanela.Advisors.FilterMethodAdvisor
import kanela.agent.libs.net.bytebuddy.implementation.bind.annotation.{Argument, SuperCall}
import kanela.agent.scala.KanelaInstrumentation

import scala.concurrent.Future

class RequestHandlerInstrumentation extends KanelaInstrumentation {

  forSubtypeOf("play.api.http.HttpFilters") { builder =>
    builder
      .withAdvisorFor(method("filters"), classOf[FilterMethodAdvisor])
      .build()
  }

  forTargetType("play.core.server.netty.PlayRequestHandler") { builder =>
    builder
      .withInterceptorFor(method("handle"), NettyHandleMethodInterceptor)
      .build()
  }

  forTargetType("play.core.server.AkkaHttpServer") { builder =>
    builder
      .withInterceptorFor(anyMethod("handleRequest", "play$core$server$AkkaHttpServer$$handleRequest"), AkkaHttpHandleRequestMethodInterceptor)
      .build()
  }
}

object AkkaHttpHandleRequestMethodInterceptor {
  import akka.http.scaladsl.model.{HttpRequest, HttpResponse}

  def onHandle(@SuperCall callable: Callable[Future[HttpResponse]], @Argument(0) request: HttpRequest): Future[HttpResponse] = {
    import kamon.play.instrumentation.AkkaHttpRequestHandlerInstrumentation._

    RequestHandlerInstrumentation.handleRequest(callable.call(), AkkaHttpGenericRequest(request))
  }
}

object NettyHandleMethodInterceptor {
  import io.netty.handler.codec.http.{HttpRequest, HttpResponse}

  def onHandle(@SuperCall callable: Callable[Future[HttpResponse]], @Argument(1) request: HttpRequest): Future[HttpResponse] = {
    import kamon.play.instrumentation.NettyRequestHandlerInstrumentation._

    RequestHandlerInstrumentation.handleRequest(callable.call(), NettyGenericRequest(request))
  }
}

object SeqUtils {
  def prepend[T](seq: Seq[T], value: T): Seq[T] = value +: seq
}


