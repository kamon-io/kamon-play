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

package kamon.play.instrumentation.kanela;

import kamon.play.OperationNameFilter;
import kamon.play.instrumentation.WSInstrumentation;
import kanela.agent.libs.net.bytebuddy.asm.Advice;
import play.api.libs.ws.WSRequest;
import play.api.mvc.EssentialFilter;
import scala.collection.Seq;

public final class Advisors {

    static final class UrlMethodAdvisor {
        /**
         * Appends Kamon filter to {@link WSRequest}.
         */
        @Advice.OnMethodExit
        public static void addKamonRequestFilter(@Advice.Return(readOnly = false) WSRequest wsRequest) {
            wsRequest = wsRequest.withRequestFilter(WSInstrumentation.requestFilter());
        }
    }

    /**
     * Advisor for play.api.http.DefaultHttpRequestHandler::new
     */
    static final class FiltersFieldAdvisor {

        @Advice.OnMethodExit
        public static void onExit(@Advice.FieldValue(value = "filters", readOnly = false) Seq<EssentialFilter> filters) {
            filters = SeqUtils.prepend(filters, new OperationNameFilter());
        }
    }
}