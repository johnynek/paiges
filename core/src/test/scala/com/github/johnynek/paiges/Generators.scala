package com.github.johnynek.paiges

import org.scalacheck.{Arbitrary, Cogen, Gen}

object Generators {
  import Doc.{ str, text }

  val asciiString: Gen[String] =
    for {
      n <- Gen.choose(1, 10)
      cs <- Gen.listOfN(n, Gen.choose(32.toChar, 126.toChar))
    } yield cs.mkString

  val generalString: Gen[String] =
    implicitly[Arbitrary[String]].arbitrary

  val doc0Gen: Gen[Doc] = Gen.frequency(
    (1, Doc.empty),
    (1, Doc.space),
    (1, Doc.line),
    (1, Doc.spaceOrLine),
    (10, asciiString.map(text(_))),
    (10, generalString.map(text(_))),
    (3, asciiString.map(Doc.split(_))),
    (3, generalString.map(Doc.split(_))),
    (3, generalString.map(Doc.paragraph(_)))
    )

  val combinators: Gen[(Doc, Doc) => Doc] =
    Gen.oneOf(
    { (a: Doc, b: Doc) => a + b },
    { (a: Doc, b: Doc) => a space b },
    { (a: Doc, b: Doc) => a / b },
    { (a: Doc, b: Doc) => a spaceOrLine b })

  val unary: Gen[Doc => Doc] =
    Gen.oneOf(
      Gen.const({ d: Doc => d.grouped }),
      Gen.choose(0, 40).map { i => { d: Doc => d.nest(i) } })

  val folds: Gen[(List[Doc] => Doc)] =
    Gen.oneOf(
    // fill is exponentially expensive currently
    { ds: List[Doc] => Doc.fill(Doc.empty, ds.take(6)) },
    { ds: List[Doc] => Doc.spread(ds) },
    { ds: List[Doc] => Doc.stack(ds) })

  val maxDepth = 7

  def genTree(depth: Int): Gen[Doc] = {
    val ugen = for {
      u <- unary
      d <- genTree(depth - 1)
    } yield u(d)

    val cgen = for {
      c <- combinators
      d0 <- genTree(depth - 1)
      d1 <- genTree(depth - 1)
    } yield c(d0, d1)

    val fgen = for {
      fold <- folds
      num <- Gen.choose(0, 20)
      ds <- Gen.listOfN(num, Gen.lzy(genTree(depth - 1)))
    } yield fold(ds)

    if (depth <= 0) doc0Gen
    else if (depth >= maxDepth - 1) {
      Gen.frequency(
        // bias to simple stuff
        (6, doc0Gen),
        (1, ugen),
        (2, cgen),
        (1, fgen))
    } else {
      // bias to simple stuff
      Gen.frequency(
        (6, doc0Gen),
        (1, ugen),
        (2, cgen))
    }
  }

  val genDoc: Gen[Doc] =
    Gen.choose(0, 7).flatMap(genTree)

  implicit val arbDoc: Arbitrary[Doc] =
    Arbitrary(genDoc)

  implicit val cogenDoc: Cogen[Doc] =
    Cogen[Int].contramap((d: Doc) => d.hashCode)
}
