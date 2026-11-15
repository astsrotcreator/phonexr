package dev.chessar.liveprobe

import kotlin.math.abs
import kotlin.math.max

/** Coordinates of the 8×8 image, top-left = 0. */
object VisualChess {
    fun boardIndex(visual: Int, whiteBottom: Boolean): Int =
        if (whiteBottom) visual else 63 - visual

    fun squareName(visual: Int, whiteBottom: Boolean): String {
        val board = boardIndex(visual, whiteBottom)
        return "${'a' + board % 8}${8 - board / 8}"
    }

    fun boardSquare(index: Int): String =
        "${'a' + index % 8}${8 - index / 8}"
}

data class SuggestedMove(val from: Int, val to: Int, val notation: String)

/**
 * Local rules filter for a fresh standard chess game.
 * The program NEVER invents piece recognition or confirms moves automatically.
 */
class ChessPosition {
    private val pieces = CharArray(64) { '.' }
    private var whiteTurn = true
    private var whiteKing = true
    private var blackKing = true
    private var whiteRookA = true
    private var whiteRookH = true
    private var blackRookA = true
    private var blackRookH = true
    private var epTarget = -1
    private var halfmoveClock = 0
    private var fullmoveNumber = 1
    var moveNumber = 0
        private set

    init { reset() }

    fun reset() {
        val rows = listOf("rnbqkbnr", "pppppppp", "........", "........",
            "........", "........", "PPPPPPPP", "RNBQKBNR")
        rows.forEachIndexed { row, contents ->
            contents.forEachIndexed { col, piece -> pieces[row * 8 + col] = piece }
        }
        whiteTurn = true; whiteKing = true; blackKing = true
        whiteRookA = true; whiteRookH = true; blackRookA = true; blackRookH = true
        epTarget = -1; halfmoveClock = 0; fullmoveNumber = 1; moveNumber = 0
    }

    fun sideName() = if (whiteTurn) "белых" else "чёрных"

    fun suggest(visualChanged: List<Int>, whiteBottom: Boolean): SuggestedMove? {
        if (visualChanged.size != 2) return null
        val a = VisualChess.boardIndex(visualChanged[0], whiteBottom)
        val b = VisualChess.boardIndex(visualChanged[1], whiteBottom)
        val ab = isLegal(a, b)
        val ba = isLegal(b, a)
        if (ab == ba) return null
        val from = if (ab) a else b
        val to = if (ab) b else a
        return SuggestedMove(from, to, VisualChess.boardSquare(from) + " → " +
            VisualChess.boardSquare(to))
    }

    fun apply(move: SuggestedMove): Boolean {
        if (!isLegal(move.from, move.to)) return false
        val from = move.from; val to = move.to
        val p = pieces[from]
        val captured = pieces[to]
        val white = p.isUpperCase()
        val fromRow = from / 8
        val toRow = to / 8
        val lastEp = epTarget
        epTarget = -1
        if (p.uppercaseChar() == 'P' && to == lastEp && captured == '.' && from % 8 != to % 8) {
            pieces[to + if (white) 8 else -8] = '.'
        }
        if (p.uppercaseChar() == 'K' && abs(to % 8 - from % 8) == 2) {
            val row = fromRow
            val kingSide = to % 8 == 6
            val rookFrom = row * 8 + if (kingSide) 7 else 0
            val rookTo = row * 8 + if (kingSide) 5 else 3
            pieces[rookTo] = pieces[rookFrom]
            pieces[rookFrom] = '.'
        }
        pieces[to] = if (p.uppercaseChar() == 'P' && (toRow == 0 || toRow == 7))
            (if (white) 'Q' else 'q') else p
        pieces[from] = '.'
        if (p.uppercaseChar() == 'P' && abs(toRow - fromRow) == 2)
            epTarget = (to + from) / 2
        if (p == 'K') whiteKing = false
        if (p == 'k') blackKing = false
        if (from == 56 || to == 56) whiteRookA = false
        if (from == 63 || to == 63) whiteRookH = false
        if (from == 0 || to == 0) blackRookA = false
        if (from == 7 || to == 7) blackRookH = false
        halfmoveClock = if (p.uppercaseChar() == 'P' || captured != '.') 0 else halfmoveClock + 1
        if (!whiteTurn) fullmoveNumber++
        whiteTurn = !whiteTurn; moveNumber++
        return true
    }

    private fun isLegal(from: Int, to: Int): Boolean {
        if (from !in 0..63 || to !in 0..63 || from == to) return false
        val p = pieces[from]
        if (p == '.' || p.isUpperCase() != whiteTurn) return false
        val dest = pieces[to]
        if (dest != '.' && dest.isUpperCase() == whiteTurn) return false
        if (dest.uppercaseChar() == 'K') return false
        val row = from / 8
        val file = from % 8
        val dstRow = to / 8
        val dstFile = to % 8
        val dr = dstRow - row
        val df = dstFile - file
        val dir = if (whiteTurn) -1 else 1
        val pseudo = when (p.uppercaseChar()) {
            'P' -> when {
                df == 0 && dr == dir && dest == '.' -> true
                df == 0 && dr == 2 * dir && row == (if (whiteTurn) 6 else 1) &&
                    dest == '.' && pieces[(row + dir) * 8 + file] == '.' -> true
                abs(df) == 1 && dr == dir &&
                    (dest != '.' && dest.isUpperCase() != whiteTurn ||
                     to == epTarget && dest == '.' &&
                     pieces[to + if (whiteTurn) 8 else -8].uppercaseChar() == 'P') -> true
                else -> false
            }
            'N' -> abs(dr) == 1 && abs(df) == 2 || abs(dr) == 2 && abs(df) == 1
            'B' -> abs(dr) == abs(df) && pathClear(from, to)
            'R' -> (dr == 0 || df == 0) && pathClear(from, to)
            'Q' -> (dr == 0 || df == 0 || abs(dr) == abs(df)) && pathClear(from, to)
            'K' -> (max(abs(dr), abs(df)) == 1) || canCastle(from, to)
            else -> false
        }
        if (!pseudo) return false
        val checkBoard = pieces.copyOf()
        if (p.uppercaseChar() == 'P' && dest == '.' && file != dstFile &&
            to == epTarget) checkBoard[to + if (whiteTurn) 8 else -8] = '.'
        if (p.uppercaseChar() == 'K' && abs(df) == 2) {
            val rookFrom = row * 8 + if (df > 0) 7 else 0
            val rookTo = row * 8 + if (df > 0) 5 else 3
            checkBoard[rookTo] = checkBoard[rookFrom]
            checkBoard[rookFrom] = '.'
        }
        checkBoard[to] = p
        checkBoard[from] = '.'
        val kingAt = if (p.uppercaseChar() == 'K') to else checkBoard.indexOf(if (whiteTurn) 'K' else 'k')
        if (kingAt == -1) return false
        return !attacked(checkBoard, kingAt, byWhite = !whiteTurn)
    }

    private fun pathClear(from: Int, to: Int): Boolean {
        val dr = (to / 8 - from / 8).compareTo(0)
        val df = (to % 8 - from % 8).compareTo(0)
        if (dr == 0 && df == 0) return false
        var r = from / 8 + dr; var f = from % 8 + df
        while (r != to / 8 || f != to % 8) {
            if (r !in 0..7 || f !in 0..7 || pieces[r * 8 + f] != '.') return false
            r += dr; f += df
        }
        return true
    }

    private fun canCastle(from: Int, to: Int): Boolean {
        if (whiteTurn && (from != 60 || !(to == 62 || to == 58))) return false
        if (!whiteTurn && (from != 4 || !(to == 6 || to == 2))) return false
        val white = whiteTurn
        val kingReady = if (white) whiteKing else blackKing
        if (!kingReady) return false
        val kingSide = to % 8 == 6
        val rookReady = if (white) (if (kingSide) whiteRookH else whiteRookA)
            else (if (kingSide) blackRookH else blackRookA)
        if (!rookReady) return false
        val row = from / 8
        val rookPos = row * 8 + if (kingSide) 7 else 0
        if (pieces[rookPos] != if (white) 'R' else 'r') return false
        val steps = if (kingSide) listOf(5, 6) else listOf(1, 2, 3)
        if (steps.any { pieces[row * 8 + it] != '.' }) return false
        if (attacked(pieces, from, !white)) return false
        val transit = row * 8 + if (kingSide) 5 else 3
        val copy = pieces.copyOf()
        copy[from] = '.'; copy[transit] = if (white) 'K' else 'k'
        if (attacked(copy, transit, !white)) return false
        return true
    }

    private fun attacked(board: CharArray, cell: Int, byWhite: Boolean): Boolean {
        val tr = cell / 8; val tf = cell % 8
        for (from in 0..63) {
            val p = board[from]
            if (p == '.' || p.isUpperCase() != byWhite) continue
            val dr = tr - from / 8
            val df = tf - from % 8
            when (p.uppercaseChar()) {
                'P' -> if (dr == (if (byWhite) -1 else 1) && abs(df) == 1) return true
                'N' -> if (abs(dr) == 1 && abs(df) == 2 || abs(dr) == 2 && abs(df) == 1) return true
                'K' -> if (max(abs(dr), abs(df)) == 1) return true
                'B', 'R', 'Q' -> {
                    val diag = abs(dr) == abs(df)
                    val straight = dr == 0 || df == 0
                    val valid = when (p.uppercaseChar()) {
                        'B' -> diag
                        'R' -> straight
                        else -> diag || straight
                    }
                    if (valid) {
                        val rs = dr.compareTo(0); val fs = df.compareTo(0)
                        var rr = from / 8 + rs; var ff = from % 8 + fs
                        var clear = true
                        while (rr != tr || ff != tf) {
                            if (board[rr * 8 + ff] != '.') { clear = false; break }
                            rr += rs; ff += fs
                        }
                        if (clear) return true
                    }
                }
            }
        }
        return false
    }

    fun fen(): String {
        val cells = (0..7).joinToString("/") { row ->
            buildString {
                var gap = 0
                for (col in 0..7) {
                    val c = pieces[row * 8 + col]
                    if (c == '.') gap++ else {
                        if (gap > 0) { append(gap); gap = 0 }
                        append(c)
                    }
                }
                if (gap > 0) append(gap)
            }
        }
        val castle = buildString {
            if (whiteKing && whiteRookH) append('K')
            if (whiteKing && whiteRookA) append('Q')
            if (blackKing && blackRookH) append('k')
            if (blackKing && blackRookA) append('q')
        }.ifEmpty { "-" }
        return "$cells ${if (whiteTurn) "w" else "b"} $castle " +
            "${if (epTarget == -1) "-" else VisualChess.boardSquare(epTarget)} $halfmoveClock $fullmoveNumber"
    }
}
