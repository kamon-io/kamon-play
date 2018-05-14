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

import kamon.Kamon
import kamon.play.instrumentation.kanela.Advisors.FiltersFieldAdvisor
import kamon.play.instrumentation.{StatusCodes, decodeContext, isError}
import kamon.trace.Span
import kamon.util.CallingThreadExecutionContext
import kanela.agent.libs.net.bytebuddy.implementation.bind.annotation.{Argument, SuperCall}
import kanela.agent.scala.KanelaInstrumentation
import play.api.mvc.EssentialFilter

import scala.concurrent.Future

class RequestHandlerInstrumentation extends KanelaInstrumentation {

  forTargetType("play.api.http.DefaultHttpRequestHandler") { builder =>
    builder
      .withAdvisorFor(Constructor.and(withArgument(3, classOf[Seq[EssentialFilter]])), classOf[FiltersFieldAdvisor])
      .build()
  }

  forTargetType("play.core.server.netty.PlayRequestHandler") { builder =>
    builder
      .withInterceptorFor(method("handle"), NettyHandleMethodInterceptor)
      .build()
  }
}

object NettyHandleMethodInterceptor {
  import io.netty.handler.codec.http.{HttpRequest, HttpResponse}

  def onHandle(@SuperCall callable: Callable[Future[HttpResponse]], @Argument(1) request: HttpRequest): Future[HttpResponse] = {
    val incomingContext = decodeContext(request)
    val serverSpan = Kamon.buildSpan("unknown-operation")
      .asChildOf(incomingContext.get(Span.ContextKey))
      .withMetricTag("span.kind", "server")
      .withMetricTag("component", "play.server.netty")
      .withMetricTag("http.method", request.getMethod.name())
      .withTag("http.url", request.getUri)
      .start()

    val responseFuture = Kamon.withContext(incomingContext.withKey(Span.ContextKey, serverSpan))(callable.call())

    responseFuture.transform(
      s = response => {
        val responseStatus = response.getStatus
        serverSpan.tag("http.status_code", responseStatus.code())

        if(isError(responseStatus.code))
          serverSpan.addError(responseStatus.reasonPhrase())

        if(responseStatus.code == StatusCodes.NotFound)
          serverSpan.setOperationName("not-found")

        serverSpan.finish()
        response
      },
      f = error => {
        serverSpan.addError("error.object", error)
        serverSpan.finish()
        error
      }
    )(CallingThreadExecutionContext)
  }
}

object SeqUtils {
  def prepend[T](seq: Seq[T], value: T): Seq[T] = value +: seq
}
