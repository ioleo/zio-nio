package zio.nio.channels

import java.io.IOException
import java.nio.channels.{ GatheringByteChannel => JGatheringByteChannel }
import java.nio.{ ByteBuffer => JByteBuffer }

import zio._
import zio.clock.Clock
import zio.nio.{ Buffer, ByteBuffer }
import zio.stream.{ ZSink, ZStream }

/**
 * A channel that can write bytes from a sequence of buffers.
 */
trait GatheringByteOps {

  import GatheringByteOps._

  protected[channels] def channel: JGatheringByteChannel

  final def write(srcs: List[ByteBuffer]): IO[IOException, Long] =
    IO.effect(channel.write(unwrap(srcs))).refineToOrDie[IOException]

  final def write(src: ByteBuffer): IO[IOException, Int] =
    IO.effect(channel.write(src.buffer)).refineToOrDie[IOException]

  /**
   * Writes a list of chunks, in order.
   *
   * Multiple writes may be performed in order to write all the chunks.
   */
  final def writeChunks(srcs: List[Chunk[Byte]]): IO[IOException, Unit] =
    for {
      bs <- IO.foreach(srcs)(Buffer.byte)
      _  <- {
        // Handle partial writes by dropping buffers where `hasRemaining` returns false,
        // meaning they've been completely written
        def go(buffers: List[ByteBuffer]): IO[IOException, Unit] =
          for {
            _     <- write(buffers)
            pairs <- IO.foreach(buffers)(b => b.hasRemaining.map(_ -> b))
            r     <- {
              val remaining = pairs.dropWhile(!_._1).map(_._2)
              go(remaining).unless(remaining.isEmpty)
            }
          } yield r
        go(bs)
      }
    } yield ()

  /**
   * Writes a chunk of bytes.
   *
   * Multiple writes may be performed to write the entire chunk.
   */
  final def writeChunk(src: Chunk[Byte]): IO[IOException, Unit] = writeChunks(List(src))

  /**
   * A sink that will write all the bytes it receives to this channel.
   * '''Note:''' This method does not work well with a channel in non-blocking mode, as it will busy-wait whenever
   * the channel is not ready for writes. The returned sink should be run within the context of a `useBlocking`
   * call for correct blocking and interruption support.
   *
   * @param bufferConstruct Optional, overrides how to construct the buffer used to transfer bytes received by the sink to this channel.
   */
  def sink(
    bufferConstruct: UIO[ByteBuffer] = Buffer.byte(5000)
  ): ZSink[Clock, IOException, Byte, Byte, Long] =
    ZSink {
      for {
        buffer   <- bufferConstruct.toManaged_
        countRef <- Ref.makeManaged(0L)
      } yield (_: Option[Chunk[Byte]])
        .map { chunk =>
          def doWrite(total: Int, c: Chunk[Byte]): ZIO[Any with Clock, IOException, Int] = {
            val x = for {
              remaining <- buffer.putChunk(c)
              _         <- buffer.flip
              count     <- ZStream
                             .repeatEffectWith(write(buffer), Schedule.recurWhileM(Function.const(buffer.hasRemaining)))
                             .runSum
              _         <- buffer.clear
            } yield (count + total, remaining)
            x.flatMap {
              case (result, remaining) if remaining.isEmpty => ZIO.succeed(result)
              case (result, remaining)                      => doWrite(result, remaining)
            }
          }

          doWrite(0, chunk).foldM(
            e => buffer.getChunk().flatMap(c => ZIO.fail((Left(e), c))),
            count => countRef.update(_ + count.toLong)
          )
        }
        .getOrElse(
          countRef.get.flatMap[Any, (Either[IOException, Long], Chunk[Byte]), Unit](count =>
            IO.fail((Right(count), Chunk.empty))
          )
        )
    }

}

object GatheringByteOps {

  private def unwrap(srcs: List[ByteBuffer]): Array[JByteBuffer] = srcs.map(d => d.buffer).toArray

}