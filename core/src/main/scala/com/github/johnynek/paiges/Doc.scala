package com.github.johnynek.paiges

import java.io.PrintWriter
import java.lang.StringBuilder

import scala.annotation.tailrec
import scala.util.matching.Regex

/**
 * implementation of Wadler's classic "A Prettier Printer"
 *
 * http://homepages.inf.ed.ac.uk/wadler/papers/prettier/prettier.pdf
 */
sealed abstract class Doc extends Product with Serializable {

  import Doc.{ Empty, Text, Line, Nest, Concat, Union }

  /**
   * Append the given Doc to this one.
   *
   * This method will automatically right-associate; that is, the
   * expression `(x + y) + z` is equivalent to `x + (y + z)`.
   */
  def +(that: Doc): Doc =
    this match {
      case Concat(x, y) => Concat(x, y + that)
      case _ => Concat(this, that)
    }

  /**
   * Prepend the given String to this Doc.
   *
   * The expression `str +: d` is equivalent to `Doc.text(str) + d`.
   */
  def +:(str: String): Doc =
    Doc.text(str) + this

  /**
   * Append the given String to this Doc.
   *
   * The expression `d :+ str` is equivalent to `d + Doc.text(str)`.
   */
  def :+(str: String): Doc =
    this + Doc.text(str)

  /**
   * Synonym for .repeat. If n > 0 repeat the doc n times,
   * else return empty
   */
  def *(n: Int): Doc =
    repeat(n)

  /**
   * Append the given Doc to this one, separated by a newline.
   */
  def /(that: Doc): Doc =
    this + Concat(Doc.line, that)

  /**
   * Append the given Doc to this one, separated by a newline.
   */
  def line(that: Doc): Doc =
    this / that

  /**
   * Prepend the given String to this Doc, separated by a newline.
   *
   * The expression `str /: d` is equivalent to `Doc.text(str) / d`.
   */
  def /:(str: String): Doc =
    Doc.text(str) + Concat(Doc.line, this)

  /**
   * Append the given String to this Doc, separated by a newline.
   *
   * The expression `d :/ str` is equivalent to `d / Doc.text(str)`.
   */
  def :/(str: String): Doc =
    this / Doc.text(str)

  /**
   * Append the given String to this one, separated by a newline.
   */
  def line(str: String): Doc =
    this :/ str

  /**
   * Append the given Doc to this one, separated by a space.
   */
  def space(that: Doc): Doc =
    this + (Doc.space + that)

  /**
   * Append the given String to this Doc, separated by a space.
   */
  def space(that: String): Doc =
    this.space(Doc.text(that))

  /**
   * Append the given Doc to this one, using a space (if there is
   * enough room), or a newline otherwise.
   */
  def spaceOrLine(that: Doc): Doc =
    this + (Doc.spaceOrLine + that)

  /**
   * Append the given String to this Doc, using a space (if there is
   * enough room), or a newline otherwise.
   */
  def spaceOrLine(that: String): Doc =
    spaceOrLine(Doc.text(that))

  /**
   * Bookend this Doc between the given Docs, separated by newlines
   * and indentation (if space permits) or spaces otherwise.
   *
   * By default, the indentation is two spaces.
   */
  def bracketBy(left: Doc, right: Doc, indent: Int = 2): Doc =
    (left + ((Doc.line + this).nest(indent) + (Doc.line + right))).grouped

  /**
   * Treat this Doc as a group that can be compressed.
   *
   * The effect of this is to replace newlines with spaces, if there
   * is enough room. Otherwise, the Doc will be rendered as-is.
   */
  def grouped: Doc =
    flattenOption match {
      case Some(flat) =>
        // todo, flat could already be in the doc
        // set. This complicates comparisons
        Union(flat, () => this)
      case None => this
    }

  /**
   * Returns true if every call to .render will return the empty
   * string (no matter what width is used); otherwise, returns false.
   */
  def isEmpty: Boolean = {
    @tailrec def loop(doc: Doc, stack: List[Doc]): Boolean =
      doc match {
        case Empty => stack match {
          case d1 :: tail => loop(d1, tail)
          case Nil => true
        }
        case Concat(_, Line) =>
          false // minor optimization to short circuit sooner
        case Concat(a, Text(s)) =>
          // minor optimization to short circuit sooner
          s.isEmpty && loop(a, stack)
        case Concat(a, b) => loop(a, b :: stack)
        case Nest(i, d) => loop(d, stack)
        case Text(s) =>
          // shouldn't be empty by construction, but defensive
          s.isEmpty && loop(Empty, stack)
        case Line => false
        case Union(flattened, _) =>
          // flattening cannot change emptiness
          loop(flattened, stack)
      }
    loop(this, Nil)
  }

  /**
   * Returns true if there is a width where these Docs render the same
   * String; otherwise, returns false.
   */
  def isSubDocOf(that: Doc): Boolean =
    DocTree.isSubDoc(DocTree.toDocTree(this), DocTree.toDocTree(that))

  /**
   * Render this Doc as a String, limiting line lengths to `width` or
   * shorter when possible.
   *
   * Note that this method does not guarantee there are no lines
   * longer than `width` -- it just attempts to keep lines within this
   * length when possible.
   */
  def render(width: Int): String = {
    val bldr = new StringBuilder
    val it = Chunk.best(width, this)
    while (it.hasNext) {
      bldr.append(it.next.str)
    }
    bldr.toString
  }

  /**
   * Render this Doc as a stream of strings, treating `width` in the
   * same way as `render` does.
   *
   * The expression `d.renderStream(w).mkString` is equivalent to
   * `d.render(w)`.
   */
  def renderStream(width: Int): Stream[String] =
    Chunk.best(width, this).map(_.str).toStream

  /**
   * If n > 0, repeat the Doc that many times, else
   * return empty
   */
  def repeat(count: Int): Doc = {
    /**
     * only have log depth, so recursion is fine
     * d * (2n + c) = (dn + dn) + c
     */
    def loop(d: Doc, cnt: Int): Doc = {
      val n = cnt >> 1
      val dn2 =
        if (n > 0) {
          val dn = loop(d, n)
          Concat(dn, dn)
        }
        else {
          Empty
        }
      if ((cnt & 1) == 1) Concat(dn2, d) else dn2
    }
    if (count <= 0) Empty
    else loop(this, count)
  }

  /**
   * Nest appends spaces to any newlines ocurring within this Doc.
   *
   * The effect of this is cumulative. For example, the expression
   * `x.nest(1).nest(2)` is equivalent to `x.nest(3)`.
   */
  def nest(amount: Int): Doc =
    this match {
      case Nest(i, d) => Nest(i + amount, d)
      case _ => Nest(amount, this)
    }

  /**
   * Render this Doc at the given `width`, and write it to the given
   * PrintWriter.
   *
   * The expression `x.writeTo(w, pw)` is equivalent to
   * `pw.print(x.render(w))`, but will usually be much more efficient.
   *
   * This method does not close `pw` or have any side-effects other
   * than the actual writing.
   */
  def writeTo(width: Int, pw: PrintWriter): Unit = {
    val it = Chunk.best(width, this)
    while(it.hasNext) {
      pw.append(it.next.str)
    }
  }

  /**
   * Compute a hash code for this Doc.
   */
  override lazy val hashCode: Int = {

    @inline def hash(curr: Int, c: Char): Int =
      curr * 1500450271 + c.toInt

    @tailrec def shash(n: Int, s: String, i: Int): Int =
      if (i < s.length) shash(hash(n, s.charAt(i)), s, i + 1) else n

    // Always go left to avoid triggering the lazy fill evaluation.
    renderStream(Int.MaxValue).foldLeft(0xdead60d5) {
      case (n, s) => shash(n, s, 0)
    }
  }

  /**
   * Return a very terse string for this Doc.
   *
   * To get a full representation of the document's internal
   * structure, see `verboseString`.
   */
  override def toString: String =
    "Doc(...)"

  /**
   * Produce a verbose string representation of this Doc.
   *
   * Unlike `render`, this method will reveal the internal tree
   * structure of the Doc (i.e. how concatenation and union nodes are
   * constructed), as well as the contents of every text node.
   *
   * By default, only the left side of union nodes is displayed. If
   * `forceUnions = true` is passed, then both sides of the union are
   * rendered (making this potentially-expensive method even more
   * expensive).
   */
  def representation(forceUnions: Boolean = false): Doc = {
    @tailrec def loop(stack: List[Either[Doc, String]], suffix: Doc): Doc =
      stack match {
        case head :: tail =>
          head match {
            case Right(s) =>
              loop(tail, s +: suffix)
            case Left(d) =>
              d match {
                case Empty =>
                  loop(tail, "Empty" +: suffix)
                case Line =>
                  loop(tail, "Line" +: suffix)
                case Text(s) =>
                  loop(tail, "Text(" +: s +: ")" +: suffix)
                case Nest(i, d) =>
                  loop(Left(d) :: Right(", ") :: Right(i.toString) :: Right("Nest(") :: tail, ")" +: suffix)
                case Concat(x, y) =>
                  loop(Left(y) :: Right(", ") :: Left(x) :: Right("Concat(") :: tail, ")" +: suffix)
                case Union(x, y) =>
                  if (forceUnions) {
                    loop(Left(y()) :: Right(", ") :: Left(x) :: Right("Union(") :: tail, ")" +: suffix)
                  } else {
                    loop(Left(x) :: Right("Union(") :: tail, ", ...)" +: suffix)
                  }
              }
          }
        case Nil =>
          suffix
      }
    loop(Left(this) :: Nil, Doc.empty)
  }

  /**
   * Compare two Docs by finding the first rendering where the strings
   * produced differ (if any).
   *
   * Note that `==` on Docs uses structural equality, whereas this
   * method will return 0 in cases where Docs are not structurally
   * equal but are semantically-equal (they will always render to the
   * same string for any width).
   *
   * This method can be very expensive in some cases, especially the
   * above-mentioned case where Docs are not structurally equal but
   * are equivalent.
   */
  def compare(that: Doc): Int =
    DocTree.compareTree(DocTree.toDocTree(this), DocTree.toDocTree(that))

  /**
   * Convert this Doc to a single-line representation.
   *
   * All newlines are replaced with spaces (and optional indentation
   * is ignored). The resulting Doc will never render any newlines, no
   * matter what width is used.
   */
  def flatten: Doc = {

    def finish(d: Doc, front: List[Doc]): Doc =
      front.foldLeft(d) { (res, f) => Concat(f, res) }

    @tailrec
    def loop(h: Doc, stack: List[Doc], front: List[Doc]): Doc =
      h match {
        case Empty | Text(_) =>
          stack match {
            case Nil => finish(h, front)
            case x :: xs => loop(x, xs, h :: front)
          }
        case Line => loop(Doc.space, stack, front)
        case Nest(i, d) => loop(d, stack, front) // no Line, so Nest is irrelevant
        case Union(a, _) => loop(a, stack, front) // invariant: flatten(union(a, b)) == flatten(a)
        case Concat(a, b) => loop(a, b :: stack, front)
      }
    loop(this, Nil, Nil)
  }

  /**
   * This method is similar to flatten, but returns None if no
   * flattening was needed (i.e. if no newlines or unions were present).
   *
   * As with flatten, the resulting Doc (if any) will never render any
   * newlines, no matter what width is used.
   */
  def flattenOption: Option[Doc] = {

    type DB = (Doc, Boolean)

    def finish(last: DB, front: List[DB]): Option[Doc] = {
     val (d, c) = front.foldLeft(last) {
        case ((d1, c1), (d0, c2)) => (Concat(d0, d1), c1 || c2)
      }
     if (c) Some(d) else None
    }

    @tailrec
    def loop(h: DB, stack: List[DB], front: List[DB]): Option[Doc] =
      h._1 match {
        case Empty | Text(_) =>
          val noChange = h
          stack match {
            case Nil => finish(h, front)
            case x :: xs => loop(x, xs, h :: front)
          }
        case Line =>
          val next = Doc.space
          val change = (next, true)
          stack match {
            case Nil => finish(change, front)
            case x :: xs => loop(x, xs, change :: front)
          }
        case Nest(i, d) =>
          /*
           * This is different from flatten which always strips
           * the Nest node. This will return None if there is
           * no embedded Line inside
           */
          loop((d, h._2), stack, front) // no Line, so Nest is irrelevant
        case Union(a, _) => loop((a, true), stack, front) // invariant: flatten(union(a, b)) == flatten(a)
        case Concat(a, b) => loop((a, h._2), (b, h._2) :: stack, front)
      }
    loop((this, false), Nil, Nil)
  }

  /**
   * Returns the largest width which may affect how this Doc
   * renders. All widths larger than this amount are guaranteed to
   * render the same.
   *
   * Note that this does not guarantee that all widths below this
   * value are distinct, just that they may be distinct. This value is
   * an upper-bound on widths that produce distinct renderings, but
   * not a least upper-bound.
   */
  def maxWidth: Int =
    Chunk.maxWidth(this)

  /**
   * Return a stream of document which represent all possible
   * renderings.
   *
   * Each document in this stream is guaranteed to render the same
   * way, no matter what width is used.
   */
  def deunioned: Stream[Doc] =
    DocTree.deunioned(DocTree.toDocTree(this))
}

object Doc {

  /**
   * Represents an empty document (the empty string).
   */
  private[paiges] case object Empty extends Doc

  /**
   * Represents a single, literal newline.
   */
  private[paiges] case object Line extends Doc

  /**
   * The string must not be empty, and may not contain newlines.
   */
  private[paiges] case class Text(str: String) extends Doc

  /**
   * Represents a concatenation of two documents.
   */
  private[paiges] case class Concat(a: Doc, b: Doc) extends Doc

  /**
   * Represents a "remembered indentation level" for a
   * document. Newlines in this document will be followed by at least
   * this much indentation (nesting is cumulative).
   */
  private[paiges] case class Nest(indent: Int, doc: Doc) extends Doc

  /**
   * Represents an optimistic rendering (on the left) as well as a
   * fallback rendering (on the right) if the first line of the left
   * is too long.
   *
   * There is an additional invariant on Union: `a == flatten(b)`.
   *
   * By construction all `Union` nodes have this property; to preserve
   * this we don't expose the `Union` constructor directly, but only
   * the `.grouped` method on Doc.
   */
  private[paiges] case class Union(a: Doc, b: () => Doc) extends Doc {
    lazy val bDoc: Doc = b()
    override def toString: String = s"Union($a, $bDoc)"
  }

  private[this] val maxSpaceTable = 20

  private[this] val spaceArray: Array[Text] =
    (1 to maxSpaceTable).map { i => Text(" " * i) }.toArray

  /**
   * Produce a document of exactly `n` spaces.
   *
   * If `n < 1`, and empty document is returned.
   */
  def spaces(n: Int): Doc =
    if (n < 1) Empty
    else if (n <= maxSpaceTable) spaceArray(n - 1)
    else Text(" " * n)

  val space: Doc = spaceArray(0)
  val comma: Doc = Text(",")
  val line: Doc = Line
  val spaceOrLine: Doc = Union(space, () => line)
  val empty: Doc = Empty

  implicit val docOrdering: Ordering[Doc] =
    new Ordering[Doc] {
      def compare(x: Doc, y: Doc): Int = x compare y
    }

  /**
   * Convert a string to text.
   *
   * This method translates newlines into an appropriate document
   * representation. The result may be much more complex than a single
   * `Text(_)` node.
   */
  def text(str: String): Doc = {
    def tx(i: Int, j: Int): Doc =
      if (i == j) Empty else Text(str.substring(i, j))

    // parse the string right-to-left, splitting at newlines.
    // this ensures that our concatenations are right-associated.
    @tailrec def parse(i: Int, limit: Int, doc: Doc): Doc =
      if (i < 0) tx(0, limit) + doc
      else str.charAt(i) match {
        case '\n' => parse(i - 1, i, Line + tx(i + 1, limit) + doc)
        case _ => parse(i - 1, limit, doc)
      }

    if (str == "") Empty
    else if (str.length == 1) {
      val c = str.charAt(0)
      if ((' ' <= c) && (c <= '~')) charTable(c.toInt - 32)
      else if (c == '\n') Line
      else Text(str)
    }
    else if (str.indexOf('\n') < 0) Text(str)
    else parse(str.length - 1, str.length, Empty)
  }

  private[this] val charTable: Array[Doc] =
    (32 to 126).map { i => Text(i.toChar.toString) }.toArray

  /**
   * Convert an arbitrary value to a Doc, using `toString`.
   *
   * This method is equivalent to `Doc.text(t.toString)`.
   */
  def str[T](t: T): Doc =
    text(t.toString)

  private val splitWhitespace: Regex = """\s+""".r

  /**
   * Convert a string to text, replacing instances of the given
   * pattern with the corresponding separator.
   *
   * Like Doc.text, this method will also lift newlines into the Doc
   * abstraction.
   *
   * The default pattern to use is `"""\s+""".r` and the default
   * separator to use is `Doc.spaceOrLine`.
   */
  def split(str: String, pat: Regex = Doc.splitWhitespace, sep: Doc = Doc.spaceOrLine): Doc =
    foldDocs(pat.pattern.split(str, -1).map(Doc.text))((x, y) => x + (sep + y))

  /**
   * Collapse a collection of documents into one document, delimited
   * by a separator and whitespace.
   *
   * The whitespace used (in addition to the separator) will be a
   * space (if there is room) or a newline otherwise.
   *
   * For example:
   *
   *     import Doc.{ text, fill }
   *     val comma = Doc.text(",")
   *     val ds = text("1") :: text("2") :: text("3") :: Nil
   *     val doc = fill(comma, ds)
   *
   *     doc.render(0)  // produces "1,\n2,\n3"
   *     doc.render(6)  // produces "1, 2,\n3"
   *     doc.render(10) // produces "1, 2, 3"
   */
  def fill(sep: Doc, ds: Iterable[Doc]): Doc = {
    @tailrec
    def fillRec(x: Doc, lst: List[Doc], stack: List[Doc => Doc]): Doc = lst match {
      case Nil => call(x, stack)
      case y :: tail =>

        /**
         * The cost of this algorithm c(n) for list of size n.
         * note that c(n) = 2 * c(n-1) + k
         * for some constant.
         * so, c(n) - c(n-1) = c(n-1) + k
         * which means that
         * c(n) = (0 until n).map(c(_)).sum + nk
         *
         * which is exponential in n (O(2^n))
         *
         * making the second parameter in the union lazy would fix this.
         * that seems an expensive fix for a single combinator. Maybe
         * there is an alternative way to express this that is not
         * exponential.
         *
         * On top of this difficulty, this formulation creates
         * Union nodes that violate the invariant that Union(a, b)
         * means a == flatten(b). It still has flatten(a) == flatten(b),
         * however. This fact seems to complicate comparison of Doc
         * which is valuable.
         */
        val xsep = x + sep
        (xsep.flattenOption, y.flattenOption) match {
          case (Some(flatx), Some(flaty)) =>
            def cont(resty: Doc) = {
              val first = flatx.space(resty)
              def second = xsep / cheatRec(y, tail)
              // note that first != second
              Union(first, () => second)
            }
            fillRec(flaty, tail, (cont _) :: stack)
          case (Some(flatx), None) =>
            def cont(resty: Doc) = {
              val first = flatx.space(resty)
              def second = xsep / resty
              // note that first != second
              Union(first, () => second)
            }
            fillRec(y, tail, (cont _) :: stack)
          case (None, Some(flaty)) =>
            def cont(resty: Doc) = {
              val first = xsep.space(resty)
              def second = xsep / cheatRec(y, tail)
              // note that first != second
              Union(first, () => second)
            }
            fillRec(flaty, tail, (cont _) :: stack)
          case (None, None) =>
            fillRec(y, tail, (xsep.spaceOrLine(_: Doc)) :: stack)
        }
    }

    def cheatRec(x: Doc, lst: List[Doc]): Doc =
      fillRec(x, lst, Nil)

    ds.toList match {
      case Nil => Empty
      case h :: tail => fillRec(h, tail, Nil)
    }
  }

  /**
   * Combine documents, using the given associative function.
   *
   * The function `fn` must be associative. That is, the expression
   * `fn(x, fn(y, z))` must be equivalent to `fn(fn(x, y), z)`.
   *
   * In practice this method builds documents from the right, so that
   * the resulting concatenations are all right-associated.
   */
  def foldDocs(ds: Iterable[Doc])(fn: (Doc, Doc) => Doc): Doc =
    if (ds.isEmpty) Doc.empty else {
      val xs = ds.toArray
      var d = xs(xs.length - 1)
      var i = xs.length - 2
      while (i >= 0) {
        d = fn(xs(i), d)
        i -= 1
      }
      d
    }

  /**
   * Split the given text into words (separated by whitespace), and
   * then join those words with a space or newline.
   *
   * This produces text which will wrap naturally at line boundaries,
   * producing a block of text.
   *
   * `paragraph` is an alias for Doc.split(s), which uses its default
   * arguments to split on whitespace and to rejoin the documents with
   * `Doc.spaceOrLine`.
   */
  def paragraph(s: String): Doc =
    split(s)

  /**
   * Concatenate the given documents together, delimited by the given
   * separator.
   *
   * For example, `intercalate(comma, List(a, b, c))` is equivalent to
   * `a + comma + b + comma + b`.
   */
  def intercalate(sep: Doc, ds: Iterable[Doc]): Doc =
    foldDocs(ds) { (a, b) => a + (sep + b) }

  /**
   * Concatenate the given documents together, delimited by spaces.
   */
  def spread(ds: Iterable[Doc]): Doc =
    intercalate(space, ds)

  /**
   * Concatenate the given documents together, delimited by newlines.
   */
  def stack(ds: Iterable[Doc]): Doc =
    intercalate(line, ds)
}
