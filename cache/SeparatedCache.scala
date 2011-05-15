/* sbt -- Simple Build Tool
 * Copyright 2009 Mark Harrah
 */
package sbt

import Types.:+:
import sbinary.{DefaultProtocol, Format, Input, Output}
import DefaultProtocol.ByteFormat
import java.io.{File, InputStream, OutputStream}

trait InputCache[I]
{
	type Internal
	def convert(i: I): Internal
	def read(from: Input): Internal
	def write(to: Output, j: Internal): Unit
	def equiv: Equiv[Internal]
}
object InputCache
{
	implicit def basicInputCache[I](implicit fmt: Format[I], eqv: Equiv[I]): InputCache[I] =
		new InputCache[I]
		{
			type Internal = I
			def convert(i: I) = i
			def read(from: Input): I = fmt.reads(from)
			def write(to: Output, i: I) = fmt.writes(to, i)
			def equiv = eqv
		}
}

class BasicCache[I,O](implicit input: InputCache[I], outFormat: Format[O]) extends Cache[I,O]
{
	def apply(file: File)(in: I) =
	{
		val j = input.convert(in)
		try { applyImpl(file, j) }
		catch { case e: Exception => Right(update(file)(j)) }
	}
	protected def applyImpl(file: File, in: input.Internal) =
	{
		Using.fileInputStream(file) { stream =>
			val previousIn = input.read(stream)
			if(input.equiv.equiv(in, previousIn))
				Left(outFormat.reads(stream))
			else
				Right(update(file)(in))
		}
	}
	protected def update(file: File)(in: input.Internal) = (out: O) =>
	{
		Using.fileOutputStream(false)(file) { stream =>
			input.write(stream, in)
			outFormat.writes(stream, out)
		}
	}
}