/*
 * Copyright (c) 2019-2020 by The Monix Project Developers.
 * See the project homepage at: https://monix.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package monix.bio

package internal

import cats.effect.CancelToken
import monix.bio.IO.{Async, Context}
import monix.execution.Scheduler
import monix.execution.atomic.{Atomic, AtomicBoolean}
import monix.execution.exceptions.UncaughtErrorException
import monix.execution.schedulers.TrampolinedRunnable

private[bio] object TaskCancellation {

  /**
    * Implementation for `Task.uncancelable`.
    */
  def uncancelable[E, A](fa: IO[E, A]): IO[E, A] =
    IO.ContextSwitch[E, A](fa, withConnectionUncancelable.asInstanceOf[Context[E] => Context[E]], restoreConnection)

  /**
    * Implementation for `Task.onCancelRaiseError`.
    */
  def raiseError[E, A](fa: IO[E, A], e: E): IO[E, A] = {
    val start = (ctx: Context[E], cb: BiCallback[E, A]) => {
      implicit val sc = ctx.scheduler
      val canCall = Atomic(true)
      // We need a special connection because the main one will be reset on
      // cancellation and this can interfere with the cancellation of `fa`
      val connChild = TaskConnection[E]()
      val conn = ctx.connection
      // Registering a special cancelable that will trigger error on cancel.
      // Note the pair `conn.pop` happens in `RaiseCallback`.
      conn.push(raiseCancelable(canCall, conn, connChild, cb, e))
      // Registering a callback that races against the cancelable we
      // registered above
      val cb2 = new RaiseCallback[E, A](canCall, conn, cb)
      IO.unsafeStartNow(fa, ctx, cb2)
    }
    Async(start, trampolineBefore = true, trampolineAfter = false, restoreLocals = false)
  }

  private final class RaiseCallback[E, A](
    waitsForResult: AtomicBoolean,
    conn: TaskConnection[E],
    cb: BiCallback[E, A]
  )(implicit s: Scheduler)
      extends BiCallback[E, A] with TrampolinedRunnable {

    private[this] var value: A = _
    private[this] var error: E = _
    private[this] var terminalError: Throwable = _

    def run(): Unit = {
      if (terminalError ne null) cb.onTermination(terminalError)
      else if (error != null) cb.onError(error)
      else cb.onSuccess(value)
    }

    def onSuccess(value: A): Unit =
      if (waitsForResult.getAndSet(false)) {
        conn.pop()
        this.value = value
        s.execute(this)
      }

    def onError(e: E): Unit =
      if (waitsForResult.getAndSet(false)) {
        conn.pop()
        this.error = e
        s.execute(this)
      } else {
        s.reportFailure(UncaughtErrorException.wrap(e))
      }

    override def onTermination(e: Throwable): Unit =
      if (waitsForResult.getAndSet(false)) {
        conn.pop()
        this.terminalError = e
        s.execute(this)
      } else {
        s.reportFailure(e)
      }
  }

  private def raiseCancelable[E, A](
    waitsForResult: AtomicBoolean,
    conn: TaskConnection[E],
    conn2: TaskConnection[E],
    cb: BiCallback[E, A],
    e: E
  ): CancelToken[UIO] = {

    IO.suspendTotal {
      if (waitsForResult.getAndSet(false))
        conn2.cancel.map { _ =>
          conn.tryReactivate()
          cb.onError(e)
        }
      else
        IO.unit
    }
  }

  private[this] val withConnectionUncancelable: Context[Any] => Context[Any] =
    ct => {
      ct.withConnection(TaskConnection.uncancelable)
        .withOptions(ct.options.disableAutoCancelableRunLoops)
    }

  private[this] def restoreConnection[E]: (Any, E, Context[E], Context[E]) => Context[E] =
    (_, _, old, ct) => {
      ct.withConnection(old.connection)
        .withOptions(old.options)
    }
}
