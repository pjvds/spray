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

package cc.spray.can
package client

import akka.event.LoggingAdapter
import cc.spray.io.pipelining._
import cc.spray.io._
import cc.spray.http.HttpRequest


/**
 * Reacts to [[cc.spray.can.HttpClient.Connect]] messages by establishing a connection to the remote host.
 * If the connection has been established successfully a new actor is spun up for the connection, which replies to the
 * sender of the [[cc.spray.can.HttpClient.Connect]] message with a [[cc.spray.can.HttpClient.Connected]] message.
 *
 * You can then send [[cc.spray.can.model.HttpRequestPart]] instances to the connection actor, which are going to be
 * replied to with [[cc.spray.can.model.HttpResponsePart]] messages (or [[akka.actor.Status.Failure]] instances
 * in case of errors).
 */
class HttpClient(ioBridge: IOBridge, settings: ClientSettings = ClientSettings())
                (implicit sslEngineProvider: ClientSSLEngineProvider) extends IOClient(ioBridge) with ConnectionActors {

  protected val pipeline: PipelineStage = HttpClient.pipeline(settings, log)

  override protected def createConnectionActor(handle: Handle): IOConnectionActor = new IOConnectionActor(handle) {
    override def receive = super.receive orElse {
      case x: HttpRequest => pipelines.commandPipeline(HttpCommand(x))
    }
  }
}

object HttpClient {

  private[can] def pipeline(settings: ClientSettings,
                            log: LoggingAdapter)
                           (implicit sslEngineProvider: ClientSSLEngineProvider): PipelineStage = {
    import settings._
    ClientFrontend(RequestTimeout, log) >>
    (ResponseChunkAggregationLimit > 0) ? ResponseChunkAggregation(ResponseChunkAggregationLimit.toInt) >>
    ResponseParsing(ParserSettings, log) >>
    RequestRendering(settings) >>
    (settings.IdleTimeout > 0) ? ConnectionTimeouts(IdleTimeout, log) >>
    SSLEncryption ? SslTlsSupport(sslEngineProvider, log, _.handle.tag == SslEnabled) >>
    (ReapingCycle > 0 && IdleTimeout > 0) ? TickGenerator(ReapingCycle)
  }

  /**
   * Object to be used as `tag` member of `Connect` commands in order to activate SSL encryption on the connection.
   * Note that SSL encryption is only generally available for the HttpClient if the respective config setting is
   * enabled. Using the `SslEnabled` tag while `SSLEncryption` is off in the settings has no effect.
   */
  case object SslEnabled

  ////////////// COMMANDS //////////////
  // HttpRequestParts +
  type Connect = IOClient.Connect;                           val Connect = IOClient.Connect
  type Close = IOClient.Close;                               val Close = IOClient.Close
  type Send = IOClient.Send;                                 val Send = IOClient.Send
  type Tell = IOClient.Tell;                                 val Tell = IOClient.Tell
  type SetRequestTimeout = ClientFrontend.SetRequestTimeout; val SetRequestTimeout = ClientFrontend.SetRequestTimeout

  ////////////// EVENTS //////////////
  // HttpResponseParts +
  val Connected = IOClient.Connected
  type Closed = IOClient.Closed;     val Closed = IOClient.Closed
  type SentOk = IOClient.SentOk;     val SentOk = IOClient.SentOk
  type Received = IOClient.Received; val Received = IOClient.Received

}

