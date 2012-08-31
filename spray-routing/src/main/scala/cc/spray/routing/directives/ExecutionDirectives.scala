/*
 * Copyright (C) 2011-2012 spray.cc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cc.spray.routing
package directives

import cc.spray.util.LoggingContext
import akka.actor._


trait ExecutionDirectives {
  import BasicDirectives._

  /**
   * Transforms exceptions thrown during evaluation of its inner route using the given
   * [[cc.spray.routing.ExceptionHandler]].
   */
  def handleExceptions(ehm: ExceptionHandlerMagnet): Directive0 =
    mapInnerRoute { inner => ctx =>
      import ehm._
      val handleError = handler andThen (_(log)(ctx))
      try inner {
        ctx.withRouteResponseHandling {
          case Status.Failure(error) if handleError.isDefinedAt(error) => handleError(error)
        }
      }
      catch handleError
    }

  /**
   * Transforms exceptions thrown during evaluation of its inner route using the given
   * [[cc.spray.routing.ExceptionHandler]].
   */
  def handleRejections(handler: RejectionHandler): Directive0 =
    mapRequestContext { ctx =>
      ctx.withRejectionHandling { rejections =>
        if (handler.isDefinedAt(rejections)) {
          val filteredRejections = RejectionHandler.applyTransformations(rejections)
          val responseForRejections = handler(filteredRejections)
          ctx.complete(responseForRejections)
        } else ctx.reject(rejections: _*)
      }
    }

  /**
   * A directive that evaluates its inner Route for every request anew. Note that this directive has no additional
   * effect, when used inside (or some level underneath) a directive extracting one or more values, since everything
   * inside a directive extracting values is _always_ reevaluated for every request.
   *
   * Also Note that this directive differs from most other directives in that it cannot be combined with other routes
   * via the usual `&` and `|` operators.
   */
  object dynamic {
    def apply(inner: => Route): Route = ctx => inner(ctx)
  }

  /**
   * Executes its inner Route in the context of the given actor.
   * Note that the parameter is a by-Name parameter, so the argument expression is going to be
   * re-evaluated for every request anew.
   */
  def detachTo(serviceActor: Route => ActorRef): Directive0 =
    mapInnerRoute { route => ctx => serviceActor(route) ! ctx }

  /**
   * Returns a function creating a new SingleRequestServiceActor for a given Route.
   */
  def singleRequestServiceActor(implicit refFactory: ActorRefFactory, eh: ExceptionHandler,
                                rh: RejectionHandler): Route => ActorRef =
    route => refFactory.actorOf(Props(new SingleRequestServiceActor(route)))
}

object ExecutionDirectives extends ExecutionDirectives


class ExceptionHandlerMagnet(val handler: ExceptionHandler, val log: LoggingContext)

object ExceptionHandlerMagnet {
  implicit def apply(handler: ExceptionHandler)(implicit log: LoggingContext) =
    new ExceptionHandlerMagnet(handler, log)
}


/**
 * An HttpService actor that reacts to an incoming RequestContext message by running it in the given Route
 * before shutting itself down.
 */
class SingleRequestServiceActor(route: Route)(implicit eh: ExceptionHandler, rh: RejectionHandler)
  extends Actor with HttpService {
  def actorRefFactory = context
  val sealedRoute = sealRoute(route)

  def receive = {
    case ctx: RequestContext =>
      try sealedRoute(ctx)
      finally context.stop(self)
  }
}