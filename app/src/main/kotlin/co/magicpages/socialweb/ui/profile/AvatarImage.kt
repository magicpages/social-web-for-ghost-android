package co.magicpages.socialweb.ui.profile

/** The part of a [width] by [height] picture that stays when it is cut to a centred square: [side] wide, from ([left], [top]). */
data class SquareCrop(val left: Int, val top: Int, val side: Int)

/** The largest centred square of a [width] by [height] picture; null when it has no area. */
fun centredSquare(width: Int, height: Int): SquareCrop? {
    if (width <= 0 || height <= 0) return null
    val side = minOf(width, height)
    return SquareCrop((width - side) / 2, (height - side) / 2, side)
}
