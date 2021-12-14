package flarogus.util

import java.awt.image.*;

object ImageUtil {
	
	/** Creates a new BufferedImage containing the result of multiplying the source image by the multiplier image */
	fun multiply(source: BufferedImage, multiplier: BufferedImage): BufferedImage {
		val new = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_ARGB);
		
		val xRatio = multiplier.width.toDouble() / source.width;
		val yRatio = multiplier.height.toDouble() / source.height
		for (x in 0 until source.width) {
			for (y in 0 until source.height) {
				val sourcecol = source.getRGB(x, y)
				val mulcol = multiplier.getRGB((x * xRatio).toInt(), (y * yRatio).toInt())
				val b = (sourcecol and 0x000000ff) * (mulcol and 0x000000ff)
				val g = ((sourcecol and 0x0000ff00) shr 8) * ((mulcol and 0x0000ff00) shr 8)
				val r = ((sourcecol and 0x00ff0000) shr 16) * ((mulcol and 0x00ff0000) shr 16)
				val a = ((sourcecol and 0x7f000000) shr 24) * ((mulcol and 0x7f000000) shr 24)
				val newcol = b or (g shl 8) or (r shl 16) or (a shl 24)
				new.setRGB(x, y, newcol)
			}
		}
		
		return new
	}
	
}