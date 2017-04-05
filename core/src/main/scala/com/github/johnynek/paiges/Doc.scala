package com.github.johnynek.paiges

import java.io.PrintWriter
import java.lang.StringBuilder

import scala.annotation.tailrec

/**
 * implementation of Wadler's classic "A Prettier Printer"
 *
 * http://homepages.inf.ed.ac.uk/wadler/papers/prettier/prettier.pdf
 */
sealed abstract class Doc extends Product with Serializable {

  /**
   * Append the given Doc to this one.
   *
   * This method will automatically right-associate; that is, the
   * expression `(x + y) + z` is equivalent to `x + (y + z)`.
   */
  def +(that: Doc): Doc =
    this match {
      case Doc.Concat(x, y) => Doc.Concat(x, y + that)
      case _ => Doc.Concat(this, that)
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
   * Append the given Doc to this one, separated by a newline.
   */
  def /(that: Doc): Doc =
    this + Doc.Concat(Doc.line, that)

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
    Doc.text(str) + Doc.Concat(Doc.line, this)

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
    Doc.group(this)

  /**
   * Returns true if every call to .render will return the empty
   * string (no matter what width is used); otherwise, returns false.
   */
  def isEmpty: Boolean =
    Doc.isEmpty(this)

  /**
   * Returns true if there is a width where these Docs render the same
   * String; otherwise, returns false.
   */
  def isSubDocOf(that: Doc): Boolean =
    Doc.isSubDoc(Doc.toDocTree(this), Doc.toDocTree(that))

  /**
   * Render this Doc as a String, limiting line lengths to `width` or
   * shorter when possible.
   *
   * Note that this method does not guarantee there are no lines
   * longer than `width` -- it just attempts to keep lines within this
   * length when possible.
   */
  def render(width: Int): String =
    Doc.render(this, width)

  /**
   * Render this Doc as a stream of strings, treating `width` in the
   * same way as `render` does.
   *
   * The expression `d.renderStream(w).mkString` is equivalent to
   * `d.render(w)`.
   */
  def renderStream(width: Int): Stream[String] =
    Doc.renderStream(this, width)

  /**
   * Nest appends spaces to any newlines ocurring within this Doc.
   *
   * The effect of this is cumulative. For example, the expression
   * `x.nest(1).nest(2)` is equivalent to `x.nest(3)`.
   */
  def nest(amount: Int): Doc =
    Doc.Nest(amount, this)

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
  def writeTo(width: Int, pw: PrintWriter): Unit =
    Doc.write(this, width, pw)

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
                case Doc.Empty =>
                  loop(tail, "Empty" +: suffix)
                case Doc.Line =>
                  loop(tail, "Line" +: suffix)
                case Doc.Text(s) =>
                  loop(tail, "Text(" +: s +: ")" +: suffix)
                case Doc.Nest(i, d) =>
                  loop(Left(d) :: Right(", ") :: Right(i.toString) :: Right("Nest(") :: tail, ")" +: suffix)
                case Doc.Concat(x, y) =>
                  loop(Left(y) :: Right(", ") :: Left(x) :: Right("Concat(") :: tail, ")" +: suffix)
                case Doc.Union(x, y) =>
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
    Doc.compareTree(Doc.toDocTree(this), Doc.toDocTree(that))
}

object Doc {

  private case object Empty extends Doc

  /**
   * Represents a single, literal newline.
   */
  private case object Line extends Doc

  /**
   * The string must not be empty, and may not contain newlines.
   */
  private case class Text(str: String) extends Doc

  private case class Concat(a: Doc, b: Doc) extends Doc

  private case class Nest(indent: Int, doc: Doc) extends Doc

  /**
   * There is an additional invariant on Union that
   * a == flatten(b). By construction all have this
   * property, but this is why we don't expose Union
   * but only .grouped
   */
  private case class Union(a: Doc, b: () => Doc) extends Doc {
    lazy val bDoc: Doc = b()
    override def toString: String = s"Union($a, $bDoc)"
  }

  private[this] val maxSpaceTable = 20
  private[this] val spaceArray: Array[Text] =
    (1 to maxSpaceTable).map { i => Text(" " * i) }.toArray

  def spaces(n: Int): Doc =
    if (n < 1) Empty
    else if (n <= maxSpaceTable) spaceArray(n - 1)
    else Text(" " * n)

  val space: Doc = spaceArray(0)

  val comma: Doc = Doc.text(",")
  val line: Doc = Line
  val spaceOrLine: Doc = Union(space, () => line)
  val empty: Doc = Empty

  implicit val docOrdering: Ordering[Doc] =
    new Ordering[Doc] {
      def compare(x: Doc, y: Doc): Int = x compare y
    }

  /**
   * Convert a string to text. Note all `\n` are converted
   * to logical entities that the rendering is aware of.
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
    else if (str == " ") space
    else if (str.indexOf('\n') < 0) Text(str)
    else parse(str.length - 1, str.length, Empty)
  }

  /**
   * Convert a T to a Doc using toString. Note that "\n" is
   * converted to a Line and is treated specially
   * by this code
   */
  def str[T](t: T): Doc =
    text(t.toString)

  /**
   * A Doc is empty if and only if all renderings are empty
   */
  def isEmpty(d: Doc): Boolean = {
    @tailrec
    def loop(doc: Doc, stack: List[Doc]): Boolean = doc match {
      case Empty => stack match {
        case d1 :: tail => loop(d1, tail)
        case Nil => true
      }
      case Concat(_, Line) => false // minor optimization to short circuit sooner
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
    loop(d, Nil)
  }

  /*
   * A variant of fillwords is fill , which collapses a list of documents into a
   * document.  It puts a space between two documents when this leads to
   * reasonable layout, and a newline otherwise
   */
  def fill(sep: Doc, ds: Iterable[Doc]): Doc = {
    def fillRec(lst: List[Doc]): Doc = lst match {
      case Nil => Empty
      case x :: Nil => x
      case x :: y :: tail =>
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
        (flattenOption(xsep), flattenOption(y)) match {
          case (Some(flatx), Some(flaty)) =>
            val resty = fillRec(flaty :: tail)
            val first = flatx.space(resty)
            def second = xsep / fillRec(y :: tail)
            // note that first != second
            Union(first, () => second)
          case (Some(flatx), None) =>
            val resty = fillRec(y :: tail)
            val first = flatx.space(resty)
            def second = xsep / resty
            // note that first != second
            Union(first, () => second)
          case (None, Some(flaty)) =>
            val resty = fillRec(flaty :: tail)
            val first = xsep.space(resty)
            def second = xsep / fillRec(y :: tail)
            // note that first != second
            Union(first, () => second)
          case (None, None) =>
            val resty = fillRec(y :: tail)
            xsep.spaceOrLine(resty)
        }
    }
    fillRec(ds.toList)
  }

  /**
   * Convert assume we can replace space
   * with newline, but newlines are preserved
   */
  def fillWords(s: String): Doc =
    foldDoc(s.split(" ", -1).map(text))(_.spaceOrLine(_))

  /**
   * split on `\s+` and foldDoc with spaceOrLine
   */
  def paragraph(s: String): Doc =
    foldDoc(s.split("\\s+", -1).map(text))(_.spaceOrLine(_))

  def foldDoc(ds: Iterable[Doc])(fn: (Doc, Doc) => Doc): Doc =
    ds.reduceOption(fn).getOrElse(Empty)

  def intercalate(d: Doc, ds: Iterable[Doc]): Doc =
    foldDoc(ds) { (a, b) => a + (d + b) }

  /**
   * intercalate with a space
   */
  def spread(ds: Iterable[Doc]): Doc = intercalate(space, ds)
  /**
   * intercalate with a newline
   */
  def stack(ds: Iterable[Doc]): Doc = intercalate(line, ds)

  /**
   * This returns a new doc where we can replace line with space
   * to fit into a line
   */
  private def group(doc: Doc): Doc =
    flattenOption(doc) match {
      case Some(flat) =>
        // todo, flat could already be in the doc
        // set. This complicates comparisons
        Union(flat, () => doc)
      case None => doc
    }

  private def renderStream(d: Doc, width: Int): Stream[String] =
    Doc2.best(width, d).map(_.str)

  private def render(d: Doc, width: Int): String = {
    val bldr = new StringBuilder
    renderStream(d, width).foreach(bldr.append(_))
    bldr.toString
  }

  private def write(d: Doc, width: Int, pw: PrintWriter): Unit = {
    renderStream(d, width).foreach(pw.append(_))
  }

  /**
   * Convert all lines to spaces and
   * return a Doc of only Empty, Text, and Concat
   * nodes
   */
  def flatten(doc: Doc): Doc = doc match {
    case Empty => Empty
    case Line => space
    case str@Text(_) => str
    case Nest(i, d) => flatten(d) // no Line, so Nest is irrelevant
    case Concat(a, b) => Concat(flatten(a), flatten(b))
    case Union(a, _) => flatten(a)
  }

  /**
   * If the doc has no Line nodes, return None, else
   * flatten the document.
   */
  def flattenOption(doc: Doc): Option[Doc] = doc match {
    case Empty | Text(_)=> None
    case Line => Some(space)
    case Nest(i, d) =>
      /*
       * This is different from flatten which always strips
       * the Nest node. This will return None if there is
       * no embedded Line inside
       */
      flattenOption(d)
    case Concat(a, b) =>
      // stack safety may be an issue here
      (flattenOption(a), flattenOption(b)) match {
        case (Some(fa), Some(fb)) => Some(Concat(fa, fb))
        case (Some(fa), None) => Some(Concat(fa, b))
        case (None, Some(fb)) => Some(Concat(a, fb))
        case (None, None) => None
      }
    case Union(a, _) => flattenOption(a).orElse(Some(a))
  }

  /**
   * What is the largest width that is relevant
   * for this Doc (all internal branches are
   * the same at this width and greater)
   *
   * val m = maxWidth(d)
   * render(d, m) == render(d, n)
   * for all n >= m
   *
   */
  def maxWidth(doc: Doc): Int = Doc2.maxWidth(doc)

  /**
   * This is the second ADT introduced for efficiency reasons
   */
  sealed abstract class Doc2 {
    def str: String
  }

  private object Doc2 {

    def best(w: Int, d: Doc): Stream[Doc2] = {
      /**
       * Return the length of this line if it fits
       */
      @tailrec
      def fits(pos: Int, d: Stream[Doc2]): Boolean =
        (w >= pos) && {
          if (d.isEmpty) true
          else d.head match {
            case Line2(_) => true
            case Text2(s) => fits(pos + s.length, d.tail)
          }
        }
      /**
       * This is not really tail recursive but many branches are, so
       * we cheat below in non-tail positions
       */
      @tailrec
      def loop(pos: Int, lst: List[(Int, Doc)]): Stream[Doc2] = lst match {
        case Nil => Stream.empty
        case (i, Empty) :: z => loop(pos, z)
        case (i, Concat(a, b)) :: z => loop(pos, (i, a) :: (i, b) :: z)
        case (i, Nest(j, d)) :: z => loop(pos, ((i + j), d) :: z)
        case (i, Text(s)) :: z => Text2(s) #:: cheat(pos + s.length, z)
        case (i, Line) :: z => Line2(i) #:: cheat(i, z)
        case (i, u@Union(x, _)) :: z =>
          /**
           * If we can fit the next line from x, we take it.
           */
          val first = cheat(pos, (i, x) :: z)
          if (fits(pos, first)) first
          else loop(pos, (i, u.bDoc) :: z)
      }

      def cheat(pos: Int, lst: List[(Int, Doc)]): Stream[Doc2] =
        loop(pos, lst)

      loop(0, (0, d) :: Nil)
    }

    /**
     * We follow the same algorithm as best, but only
     * track what the largest width is that triggers
     * a branch to the left
     */
    def maxWidth(d: Doc): Int = {
      /**
       * Return the length of this line
       */
      @tailrec
      def lineSize(pos: Int, d: Stream[Doc2]): Int =
        if (d.isEmpty) pos
        else d.head match {
          case Line2(_) => pos
          case Text2(s) => lineSize(pos + s.length, d.tail)
        }

      /**
       * This is not really tail recursive but many branches are, so
       * we cheat below in non-tail positions
       */
      @tailrec
      def loop(pos: Int, lst: List[(Int, Doc)], max: Int): Stream[(Int, Doc2)] = lst match {
        case Nil => Stream.empty
        case (i, Empty) :: z => loop(pos, z, max)
        case (i, Concat(a, b)) :: z => loop(pos, (i, a) :: (i, b) :: z, max)
        case (i, Nest(j, d)) :: z => loop(pos, ((i + j), d) :: z, max)
        case (i, Text(s)) :: z => (max, Text2(s)) #:: cheat(pos + s.length, z, max)
        case (i, Line) :: z => (max, Line2(i)) #:: cheat(i, z, max)
        case (i, Union(x, _)) :: z =>
          val first = cheat(pos, (i, x) :: z, max)
          val neededWidth = lineSize(pos, first.map(_._2))
          /**
           * if width >= neededWidth, we would branch left here (to x)
           * else we go right
           */
          if (neededWidth <= max) first
          else loop(pos, (i, x) :: z, neededWidth)
      }

      def cheat(pos: Int, lst: List[(Int, Doc)], max: Int): Stream[(Int, Doc2)] =
        loop(pos, lst, max)

      loop(0, (0, d) :: Nil, 0)
        .map(_._1)
        .reduceOption(_ max _)
        .getOrElse(0)
    }

    private[this] val indentMax = 100
    private[this] val indentTable: Array[String] =
      (0 to indentMax).iterator
        .map(makeIndentStr)
        .toArray

    def makeIndentStr(i: Int): String = "\n" + (" " * i)

    def lineToStr(indent: Int): String =
      if (indent <= indentMax) indentTable(indent)
      else makeIndentStr(indent)

    case class Text2(str: String) extends Doc2
    case class Line2(indent: Int) extends Doc2 {
      def str: String = lineToStr(indent)
    }
  }

  import Doc2.{ Line2, Text2 }

  private[paiges] case class Fix[F[_]](unfix: F[Fix[F]])
  private[paiges] type StreamTree[T] = Stream[Either[(T, () => T), Doc2]]
  private[paiges] type DocTree = Fix[StreamTree]

  private def docTree(s: StreamTree[DocTree]): DocTree = Fix[StreamTree](s)

  private[paiges] def toDocTree(d: Doc): DocTree = {
    case class Bounds(min: Int, max: Int) {
      def contains(x: Int): Boolean = (min <= x) && (x < max)

      def split(x: Int): Option[(Option[Bounds], Bounds)] =
        if (contains(x)) Some((if (x > min) Some(Bounds(min, x)) else None, Bounds(x, max)))
        else None
    }

    /**
     * Return the minimum width needed to go down
     * the left branch.
     *
     * Note we carry the current minimum (minV) in
     * order to stop early.
     */
    @tailrec
    def fits(pos: Int, d: DocTree, minV: Int): Int = d.unfix match {
      case Stream.Empty => pos min minV// we always can fit by going left
      case Right(Line2(_)) #:: _ => pos min minV
      case Right(Text2(s)) #:: tail =>
        val nextPos = pos + s.length
        if (nextPos >= minV) minV
        else fits(nextPos, docTree(tail), minV)
      case Left((a, b)) #:: _ =>
        val amin = cheatFits(pos, a, minV)
        fits(pos, b(), amin)
    }
    def cheatFits(pos: Int, d: DocTree, minV: Int): Int = fits(pos, d, minV)

    @tailrec
    def loop(pos: Int, lst: List[(Int, Doc)], bounds: Bounds): DocTree = lst match {
      case Nil => docTree(Stream.empty)
      case (i, Empty) :: z => loop(pos, z, bounds)
      case (i, Concat(a, b)) :: z => loop(pos, (i, a) :: (i, b) :: z, bounds)
      case (i, Nest(j, d)) :: z => loop(pos, ((i + j), d) :: z, bounds)
      case (i, Text(s)) :: z => docTree(Right(Text2(s)) #:: cheat(pos + s.length, z, bounds).unfix)
      case (i, Line) :: z => docTree(Right(Line2(i)) #:: cheat(i, z, bounds).unfix)
      case (i, u@Union(a, _)) :: z =>
        /**
         * if we can go left, we do, otherwise we go right. So, in the current
         * bounds, there is a threshold for the current node:
         * if (w < wmin) go right
         * else go left
         */
        val as = cheat(pos, (i, a) :: z, bounds)
        val minLeftWidth = fits(pos, as, Int.MaxValue)
        bounds.split(minLeftWidth) match {
          case None =>
            // cannot go left
            loop(pos, (i, u.bDoc) :: z, bounds)
          case Some((None, _)) =>
            // always go left, because bounds.min == minLeftWidth
            as
          case Some((Some(rb), lb)) => // note when the width is smaller we go right
            val left = cheat(pos, (i, a) :: z, lb)
            def right = cheat(pos, (i, u.bDoc) :: z, rb)
            docTree(Stream(Left((left, () => right))))
        }
    }

    def cheat(pos: Int, lst: List[(Int, Doc)], bounds: Bounds) =
      loop(pos, lst, bounds)

    loop(0, (0, d) :: Nil, Bounds(0, Int.MaxValue))
  }

  private[paiges] def deunioned(d: DocTree): Stream[Doc] = {

    def cat(a: Doc, b: Doc) = a match {
      case Empty => b
      case other => b match {
        case Empty => other
        case oo => Concat(other, oo)
      }
    }
    @tailrec
    def loop(tree: DocTree, prefix: Doc): Stream[Doc] = tree.unfix match {
      case Stream.Empty => Stream(prefix)
      case (Right(Text2(t)) #:: tail) =>
        loop(docTree(tail), cat(prefix, Text(t)))
      case (Right(Line2(n)) #:: tail) =>
        loop(docTree(tail), cat(prefix, cat(Line, spaces(n))))
      case (Left((a, b)) #:: _) =>
        cheat(a, prefix) #::: cheat(b(), prefix)
    }
    def cheat(tree: DocTree, prefix: Doc): Stream[Doc] = loop(tree, prefix)

    loop(d, Empty)
  }

  /**
   * Return all the possible Docs WITHOUT any union
   * nodes
   */
  def deunioned(doc: Doc): Stream[Doc] =
    deunioned(toDocTree(doc))

  private def push(d: Doc2, t: DocTree): DocTree =
    docTree(Right(d) #:: t.unfix)

  private def liftUnion(f: DocTree): Either[(DocTree, () => DocTree), DocTree] = f.unfix match {
    case Stream.Empty => Right(docTree(Stream.empty))
    case (Right(r) #:: tail) =>
      liftUnion(docTree(tail)) match {
        case Right(t) => Right(push(r, t))
        case Left((a, b)) => Left((push(r, a), () => push(r, b())))
      }
    case (Left(left) #:: _) => Left(left)
  }

  private def space2(n: Int) = Right(Text2(" " * n)) // can memoize this

  private def extract(s: String, part: String): Option[Text2] =
    if (s.startsWith(part)) Some(Text2(s.substring(part.length)))
    else None

  /**
   * Remove b from a
   *
   * consider None to be the empty set, which can't otherwise
   * be represented
   */
  private[paiges] def setDiff(a: DocTree, b: DocTree): Option[DocTree] =
    (a.unfix, b.unfix) match {
      case (Left((as, bs)) #:: _, _) =>
        val adiff = setDiff(as, b)
        def bdiff = setDiff(bs(), b)
        adiff match {
          case Some(ad) =>
            bdiff match {
              case Some(bd) => Some(docTree(Left((ad, () => bd)) #:: Stream.empty))
              case None => adiff
            }
          case None => bdiff
        }
      case (_, Left((as, bs)) #:: _) =>
        liftUnion(a) match {
          case Left(left) =>
            setDiff(docTree(Left(left) #:: Stream.empty), b)
          case Right(nounion) =>
            setDiff(nounion, as) match {
              case None => // we have already emptied, so we are done:
                None
              case Some(remainder) =>
                setDiff(remainder, bs())
            }
        }
      case (Stream.Empty, Stream.Empty) => None // this is now the empty set
      case (Stream.Empty, _) => Some(a)
      case (Right(Line2(nx)) #:: tailx, (right@Right(Line2(ny))) #:: taily) if (nx == ny) =>
        setDiff(docTree(tailx), docTree(taily)).map { diff =>
          docTree(right #:: diff.unfix)
        }
      case ((left@Right(Text2(s1))) #:: xtail, (right@Right(Text2(s2))) #:: ytail) =>
        if (s1.length == s2.length) {
         if (s1 == s2) {
           setDiff(docTree(xtail), docTree(ytail)).map { diff =>
             docTree(right #:: diff.unfix)
           }
         }
         else Some(a)
        }
        else if (s1.length < s2.length) {
          extract(s2, s1) match {
            case Some(t) =>
              setDiff(docTree(xtail), docTree(Right(t) #:: ytail)).map { diff =>
                docTree(left #:: diff.unfix)
              }
            case None => Some(a)
          }
        } else {
          extract(s1, s2) match {
            case Some(t) =>
              setDiff(docTree(Right(t) #:: xtail), docTree(ytail)).map { diff =>
                docTree(right #:: diff.unfix)
              }
            case None => Some(a)
          }
        }
      case (_, _) => Some(a) // they don't match at this point
    }

  /**
   * does the docTree on the left contain the one on right
   */
  def isSubDoc(a: DocTree, b: DocTree): Boolean =
    (a.unfix, b.unfix) match {
      case (Left((as, bs)) #:: _, _) =>
        isSubDoc(as, b) && isSubDoc(bs(), b)
      case (_, Left((as, bs)) #:: _) =>
        liftUnion(a) match {
          case Left((aa, ab)) =>
            isSubDoc(docTree(Left((aa, ab)) #:: Stream.empty), b)
          case Right(nounion) =>
            isSubDoc(nounion, as) || isSubDoc(nounion, bs())
        }
      case (Stream.Empty, Stream.Empty) => true
      case (Stream.Empty, _) => false
      case (_, Stream.Empty) => false
      case (Right(Line2(nx)) #:: tailx, Right(Line2(ny)) #:: taily) =>
        if (nx == ny) isSubDoc(docTree(tailx), docTree(taily))
        else {
          val m = nx min ny
          // pull the space out
          isSubDoc(docTree(space2(nx - m) #:: tailx), docTree(space2(ny - m) #:: taily))
        }
      case (Right(Line2(_)) #:: _, _) => false // line comes after text (different from ascii!)
      case (_, Right(Line2(_)) #:: _) => false
      case (Right(Text2(s1)) #:: xtail, Right(Text2(s2)) #:: ytail) =>
        if (s1.length == s2.length) {
          (s1 == s2) && isSubDoc(docTree(xtail), docTree(ytail))
        }
        else if (s1.length < s2.length) {
          extract(s2, s1) match {
            case Some(t) => isSubDoc(docTree(xtail), docTree(Right(t) #:: ytail))
            case None => false
          }
        } else {
          extract(s1, s2) match {
            case Some(t) => isSubDoc(docTree(Right(t) #:: xtail), docTree(ytail))
            case None => false
          }
        }
    }

  /**
   * The main trick is that Union(a, b) has the property that a is less than or equal to b
   * in our sort by construction. So, we can first compare on the left,
   * and if that is equal, go to right.
   */
  def compareTree(xs: DocTree, ys: DocTree): Int = {
    (xs.unfix, ys.unfix) match {
      case (Left((xa, xb)) #:: _, Left((ya, yb)) #:: _) =>
        val c1 = compareTree(xa, ya)
        if (c1 == 0) {
          /**
           * now we should compare(xb - xa, yb - ya)
           */
          setDiff(xb(), xa) match {
            case None =>
              // here xa == xb()
              setDiff(yb(), ya) match {
                case None =>
                  // yb == ya, but xa == yb
                  0
                case Some(ydiff) =>
                  compareTree(xa, ydiff)
              }
            case Some(xdiff) =>
              setDiff(yb(), ya) match {
                case None =>
                  // yb == ya
                  // we know that xa == ya, and yb == ya
                  // so xdiff != ya, but can we say > ya?
                  compareTree(xdiff, ya)
                case Some(ydiff) =>
                  compareTree(xdiff, ydiff)
              }
          }
        }
        else c1
      case (_, Left((as, bs)) #:: _) =>
        liftUnion(xs) match {
          case Right(nonunion) =>
            val c1 = compareTree(nonunion, as)
            if (c1 == 0) compareTree(nonunion, bs())
            else c1
          case Left(un) => compareTree(docTree(Left(un) #:: Stream.empty), ys)
        }
      case (Left((as, bs)) #:: _, _) =>
        liftUnion(ys) match {
          case Right(nonunion) =>
            val c1 = compareTree(as, nonunion)
            if (c1 == 0) compareTree(bs(), nonunion)
            else c1
          case Left(un) => compareTree(xs, docTree(Left(un) #:: Stream.empty))
        }
      case (Stream.Empty, Stream.Empty) => 0
      case (Stream.Empty, _) => -1
      case (_, Stream.Empty) => 1
      case (Right(Line2(nx)) #:: tailx, Right(Line2(ny)) #:: taily) =>
        if (nx == ny) compareTree(docTree(tailx), docTree(taily))
        else {
          val m = nx min ny
          // pull the space out
          compareTree(docTree(space2(nx - m) #:: tailx), docTree(space2(ny - m) #:: taily))
        }
      case (Right(Line2(_)) #:: _, _) => 1 // line comes after text (different from ascii!)
      case (_, Right(Line2(_)) #:: _) => -1
      case (Right(Text2(s1)) #:: xtail, Right(Text2(s2)) #:: ytail) =>
        if (s1.length == s2.length) {
          val c = s1 compare s2
          if (c == 0) compareTree(docTree(xtail), docTree(ytail))
          else c
        }
        else if (s1.length < s2.length) {
          extract(s2, s1) match {
            case Some(t) => compareTree(docTree(xtail), docTree(Right(t) #:: ytail))
            case None => s1 compare s2
          }
        } else {
          extract(s1, s2) match {
            case Some(t) => compareTree(docTree(Right(t) #:: xtail), docTree(ytail))
            case None => s1 compare s2
          }
        }
    }
  }
}
