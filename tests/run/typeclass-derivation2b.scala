import scala.collection.mutable
import scala.annotation.tailrec

// Simulation of typeclass derivation encoding that's currently implemented.
// The real typeclass derivation is tested in typeclass-derivation3.scala.
object TypeLevel {

  object EmptyProduct extends Product {
    def canEqual(that: Any): Boolean = true
    def productElement(n: Int) = throw new IndexOutOfBoundsException
    def productArity = 0
  }

  /** Helper class to turn arrays into products */
  class ArrayProduct(val elems: Array[AnyRef]) extends Product {
    def canEqual(that: Any): Boolean = true
    def productElement(n: Int) = elems(n)
    def productArity = elems.length
    override def productIterator: Iterator[Any] = elems.iterator
    def update(n: Int, x: Any) = elems(n) = x.asInstanceOf[AnyRef]
  }

  abstract class Generic[T] {
    type Shape <: Tuple
  }

  abstract class GenericSum[S] extends Generic[S] {
    def ordinal(x: S): Int
    inline def alternative(inline n: Int): GenericProduct[_ <: S]
  }

  abstract class GenericProduct[P] extends Generic[P] {
    type Prod = P
    def toProduct(x: P): Product
    def fromProduct(p: Product): P
  }
}

// An algebraic datatype
sealed trait Lst[+T] // derives Eq, Pickler, Show

object Lst {
  // common compiler-generated infrastructure
  import TypeLevel._

  class GenericLst[T] extends GenericSum[Lst[T]] {
    override type Shape = (Cons[T], Nil.type)
    def ordinal(x: Lst[T]) = x match {
      case x: Cons[_] => 0
      case Nil => 1
    }
    inline def alternative(inline n: Int) <: GenericProduct[_ <: Lst[T]] =
      inline n match {
        case 0 => Cons.GenericCons[T]
        case 1 => Nil.GenericNil
      }
  }

  implicit def GenericLst[T]: GenericLst[T] = new GenericLst[T]

  case class Cons[T](hd: T, tl: Lst[T]) extends Lst[T]

  object Cons {
    class GenericCons[T] extends GenericProduct[Cons[T]] {
      type Shape = (T, Lst[T])
      def toProduct(x: Cons[T]): Product = x
      def fromProduct(p: Product): Cons[T] =
        Cons(p.productElement(0).asInstanceOf[T],
             p.productElement(1).asInstanceOf[Cons[T]])
    }
    implicit def GenericCons[T]: GenericCons[T] = new GenericCons[T]
  }
  case object Nil extends Lst[Nothing] {
    class GenericNil extends GenericProduct[Nil.type] {
      type Shape = Unit
      def toProduct(x: Nil.type): Product = EmptyProduct
      def fromProduct(p: Product): Nil.type = Nil
    }
    implicit def GenericNil: GenericNil = new GenericNil
  }

  // three clauses that could be generated from a `derives` clause
  implicit def derived$Eq[T: Eq]: Eq[Lst[T]] = Eq.derived
  //implicit def derived$Pickler[T: Pickler]: Pickler[Lst[T]] = Pickler.derived
  //implicit def derived$Show[T: Show]: Show[Lst[T]] = Show.derived
}

// A typeclass
trait Eq[T] {
  def eql(x: T, y: T): Boolean
}

object Eq {
  import scala.compiletime.{erasedValue, summonInline}
  import TypeLevel._

  inline def tryEql[T](x: T, y: T) = summonInline[Eq[T]].eql(x, y)

  inline def eqlElems[Elems <: Tuple](x: Product, y: Product, n: Int): Boolean =
    inline erasedValue[Elems] match {
      case _: (elem *: elems1) =>
        tryEql[elem](
          x.productElement(n).asInstanceOf[elem],
          y.productElement(n).asInstanceOf[elem]) &&
        eqlElems[elems1](x, y, n + 1)
      case _: Unit =>
        true
    }

  inline def eqlCases[T, Alts <: Tuple](x: T, y: T, genSum: GenericSum[T], ord: Int, inline n: Int): Boolean =
    inline erasedValue[Alts] match {
      case _: (alt *: alts1) =>
        if (ord == n)
          inline genSum.alternative(n) match {
            case cas =>
              eqlElems[cas.Shape](
                cas.toProduct(x.asInstanceOf[cas.Prod]),
                cas.toProduct(y.asInstanceOf[cas.Prod]),
                0)
          }
        else eqlCases[T, alts1](x, y, genSum, ord, n + 1)
      case _: Unit =>
        false
    }

  inline def derived[T](implicit ev: Generic[T]): Eq[T] = new Eq[T] {
    def eql(x: T, y: T): Boolean = {
      inline ev match {
        case evv: GenericSum[T] =>
          val ord = evv.ordinal(x)
          ord == evv.ordinal(y) && eqlCases[T, evv.Shape](x, y, evv, ord, 0)
        case evv: GenericProduct[T] =>
          eqlElems[evv.Shape](evv.toProduct(x), evv.toProduct(y), 0)
      }
    }
  }

  implicit object IntEq extends Eq[Int] {
    def eql(x: Int, y: Int) = x == y
  }
}

object Test extends App {
  import TypeLevel._
  val eq = implicitly[Eq[Lst[Int]]]
  val xs = Lst.Cons(11, Lst.Cons(22, Lst.Cons(33, Lst.Nil)))
  val ys = Lst.Cons(11, Lst.Cons(22, Lst.Nil))
  assert(eq.eql(xs, xs))
  assert(!eq.eql(xs, ys))
  assert(!eq.eql(ys, xs))
  assert(eq.eql(ys, ys))

  val eq2 = implicitly[Eq[Lst[Lst[Int]]]]
  val xss = Lst.Cons(xs, Lst.Cons(ys, Lst.Nil))
  val yss = Lst.Cons(xs, Lst.Nil)
  assert(eq2.eql(xss, xss))
  assert(!eq2.eql(xss, yss))
  assert(!eq2.eql(yss, xss))
  assert(eq2.eql(yss, yss))
}

/*
// A simple product type
case class Pair[T](x: T, y: T) // derives Eq, Pickler, Show

object Pair {
  // common compiler-generated infrastructure
  import TypeLevel._

  val genericClass = new GenericClass("Pair\000x\000y")
  import genericClass.mirror

  private type ShapeOf[T] = Shape.Case[Pair[T], (T, T)]

  implicit def GenericPair[T]: Generic[Pair[T]] { type Shape = ShapeOf[T] } =
    new Generic[Pair[T]] {
      type Shape = ShapeOf[T]
      def reflect(xy: Pair[T]) =
        mirror(0, xy)
      def reify(c: Mirror): Pair[T] =
        Pair(c(0).asInstanceOf, c(1).asInstanceOf)
      def common = genericClass
    }

  // clauses that could be generated from a `derives` clause
  implicit def derived$Eq[T: Eq]: Eq[Pair[T]] = Eq.derived
  implicit def derived$Pickler[T: Pickler]: Pickler[Pair[T]] = Pickler.derived
  implicit def derived$Show[T: Show]: Show[Pair[T]] = Show.derived
}

sealed trait Either[+L, +R] extends Product with Serializable // derives Eq, Pickler, Show
case class Left[L](x: L) extends Either[L, Nothing]
case class Right[R](x: R) extends Either[Nothing, R]

object Either {
  import TypeLevel._

  val genericClass = new GenericClass("Left\000x\001Right\000x")
  import genericClass.mirror

  private type ShapeOf[L, R] = Shape.Cases[(
    Shape.Case[Left[L], L *: Unit],
    Shape.Case[Right[R], R *: Unit]
  )]

  implicit def GenericEither[L, R]: Generic[Either[L, R]] { type Shape = ShapeOf[L, R] } =
    new Generic[Either[L, R]] {
      type Shape = ShapeOf[L, R]
      def reflect(e: Either[L, R]): Mirror = e match {
        case e: Left[L] => mirror(0, e)
        case e: Right[R] => mirror(1, e)
      }
      def reify(c: Mirror): Either[L, R] = c.ordinal match {
        case 0 => Left(c(0).asInstanceOf)
        case 1 => Right(c(0).asInstanceOf)
      }
      def common = genericClass
    }

  implicit def derived$Eq[L: Eq, R: Eq]: Eq[Either[L, R]] = Eq.derived
  implicit def derived$Pickler[L: Pickler, R: Pickler]: Pickler[Either[L, R]] = Pickler.derived
  implicit def derived$Show[L: Show, R: Show]: Show[Either[L, R]] = Show.derived
}

// A typeclass
trait Eq[T] {
  def eql(x: T, y: T): Boolean
}

object Eq {
  import scala.compiletime.erasedValue
  import TypeLevel._

  inline def tryEql[T](x: T, y: T) = summonFrom {
    case eq: Eq[T] => eq.eql(x, y)
  }

  inline def eqlElems[Elems <: Tuple](xm: Mirror, ym: Mirror, n: Int): Boolean =
    inline erasedValue[Elems] match {
      case _: (elem *: elems1) =>
        tryEql[elem](xm(n).asInstanceOf, ym(n).asInstanceOf) &&
        eqlElems[elems1](xm, ym, n + 1)
      case _: Unit =>
        true
    }

  inline def eqlCases[Alts <: Tuple](xm: Mirror, ym: Mirror, n: Int): Boolean =
    inline erasedValue[Alts] match {
      case _: (Shape.Case[alt, elems] *: alts1) =>
        if (xm.ordinal == n) eqlElems[elems](xm, ym, 0)
        else eqlCases[alts1](xm, ym, n + 1)
     case _: Unit =>
        false
    }

  inline def derived[T, S <: Shape](implicit ev: Generic[T]): Eq[T] = new {
    def eql(x: T, y: T): Boolean = {
      val xm = ev.reflect(x)
      val ym = ev.reflect(y)
      inline erasedValue[ev.Shape] match {
        case _: Shape.Cases[alts] =>
          xm.ordinal == ym.ordinal &&
          eqlCases[alts](xm, ym, 0)
        case _: Shape.Case[_, elems] =>
          eqlElems[elems](xm, ym, 0)
      }
    }
  }

  implicit object IntEq extends Eq[Int] {
    def eql(x: Int, y: Int) = x == y
  }
}

// Another typeclass
trait Pickler[T] {
  def pickle(buf: mutable.ListBuffer[Int], x: T): Unit
  def unpickle(buf: mutable.ListBuffer[Int]): T
}

object Pickler {
  import scala.compiletime.{erasedValue, constValue}
  import TypeLevel._

  def nextInt(buf: mutable.ListBuffer[Int]): Int = try buf.head finally buf.trimStart(1)

  inline def tryPickle[T](buf: mutable.ListBuffer[Int], x: T): Unit = summonFrom {
    case pkl: Pickler[T] => pkl.pickle(buf, x)
  }

  inline def pickleElems[Elems <: Tuple](buf: mutable.ListBuffer[Int], elems: Mirror, n: Int): Unit =
    inline erasedValue[Elems] match {
      case _: (elem *: elems1) =>
        tryPickle[elem](buf, elems(n).asInstanceOf[elem])
        pickleElems[elems1](buf, elems, n + 1)
      case _: Unit =>
    }

  inline def pickleCases[Alts <: Tuple](buf: mutable.ListBuffer[Int], xm: Mirror, n: Int): Unit =
    inline erasedValue[Alts] match {
      case _: (Shape.Case[alt, elems] *: alts1) =>
        if (xm.ordinal == n) pickleElems[elems](buf, xm, 0)
        else pickleCases[alts1](buf, xm, n + 1)
      case _: Unit =>
    }

  inline def tryUnpickle[T](buf: mutable.ListBuffer[Int]): T = summonFrom {
    case pkl: Pickler[T] => pkl.unpickle(buf)
  }

  inline def unpickleElems[Elems <: Tuple](buf: mutable.ListBuffer[Int], elems: Array[AnyRef], n: Int): Unit =
    inline erasedValue[Elems] match {
      case _: (elem *: elems1) =>
        elems(n) = tryUnpickle[elem](buf).asInstanceOf[AnyRef]
        unpickleElems[elems1](buf, elems, n + 1)
      case _: Unit =>
    }

  inline def unpickleCase[T, Elems <: Tuple](gen: Generic[T], buf: mutable.ListBuffer[Int], ordinal: Int): T = {
    inline val size = constValue[Tuple.Size[Elems]]
    inline if (size == 0)
      gen.reify(gen.common.mirror(ordinal))
    else {
      val elems = new Array[Object](size)
      unpickleElems[Elems](buf, elems, 0)
      gen.reify(gen.common.mirror(ordinal, elems))
    }
  }

  inline def unpickleCases[T, Alts <: Tuple](r: Generic[T], buf: mutable.ListBuffer[Int], ordinal: Int, n: Int): T =
    inline erasedValue[Alts] match {
      case _: (Shape.Case[_, elems] *: alts1) =>
        if (n == ordinal) unpickleCase[T, elems](r, buf, ordinal)
        else unpickleCases[T, alts1](r, buf, ordinal, n + 1)
      case _ =>
        throw new IndexOutOfBoundsException(s"unexpected ordinal number: $ordinal")
    }

  inline def derived[T, S <: Shape](implicit ev: Generic[T]): Pickler[T] = new {
    def pickle(buf: mutable.ListBuffer[Int], x: T): Unit = {
      val xm = ev.reflect(x)
      inline erasedValue[ev.Shape] match {
        case _: Shape.Cases[alts] =>
          buf += xm.ordinal
          pickleCases[alts](buf, xm, 0)
        case _: Shape.Case[_, elems] =>
          pickleElems[elems](buf, xm, 0)
      }
    }
    def unpickle(buf: mutable.ListBuffer[Int]): T = inline erasedValue[ev.Shape] match {
      case _: Shape.Cases[alts] =>
        unpickleCases[T, alts](ev, buf, nextInt(buf), 0)
      case _: Shape.Case[_, elems] =>
        unpickleCase[T, elems](ev, buf, 0)
    }
  }

  implicit object IntPickler extends Pickler[Int] {
    def pickle(buf: mutable.ListBuffer[Int], x: Int): Unit = buf += x
    def unpickle(buf: mutable.ListBuffer[Int]): Int = nextInt(buf)
  }
}

// A third typeclass, making use of labels
trait Show[T] {
  def show(x: T): String
}
object Show {
  import scala.compiletime.erasedValue
  import TypeLevel._

  inline def tryShow[T](x: T): String = summonFrom {
    case s: Show[T] => s.show(x)
  }

  inline def showElems[Elems <: Tuple](elems: Mirror, n: Int): List[String] =
    inline erasedValue[Elems] match {
      case _: (elem *: elems1) =>
        val formal = elems.elementLabel(n)
        val actual = tryShow[elem](elems(n).asInstanceOf)
        s"$formal = $actual" :: showElems[elems1](elems, n + 1)
      case _: Unit =>
        Nil
    }

  inline def showCases[Alts <: Tuple](xm: Mirror, n: Int): String =
    inline erasedValue[Alts] match {
      case _: (Shape.Case[alt, elems] *: alts1) =>
        if (xm.ordinal == n) showElems[elems](xm, 0).mkString(", ")
        else showCases[alts1](xm, n + 1)
      case _: Unit =>
        throw new MatchError(xm)
    }

  inline def derived[T, S <: Shape](implicit ev: Generic[T]): Show[T] = new {
    def show(x: T): String = {
      val xm = ev.reflect(x)
      val args = inline erasedValue[ev.Shape] match {
        case _: Shape.Cases[alts] =>
          showCases[alts](xm, 0)
        case _: Shape.Case[_, elems] =>
          showElems[elems](xm, 0).mkString(", ")
      }
      s"${xm.caseLabel}($args)"
    }
  }

  implicit object IntShow extends Show[Int] {
    def show(x: Int): String = x.toString
  }
}

// Tests
object Test extends App {
  import TypeLevel._
  val eq = implicitly[Eq[Lst[Int]]]
  val xs = Lst.Cons(11, Lst.Cons(22, Lst.Cons(33, Lst.Nil)))
  val ys = Lst.Cons(11, Lst.Cons(22, Lst.Nil))
  assert(eq.eql(xs, xs))
  assert(!eq.eql(xs, ys))
  assert(!eq.eql(ys, xs))
  assert(eq.eql(ys, ys))

  val eq2 = implicitly[Eq[Lst[Lst[Int]]]]
  val xss = Lst.Cons(xs, Lst.Cons(ys, Lst.Nil))
  val yss = Lst.Cons(xs, Lst.Nil)
  assert(eq2.eql(xss, xss))
  assert(!eq2.eql(xss, yss))
  assert(!eq2.eql(yss, xss))
  assert(eq2.eql(yss, yss))

  val buf = new mutable.ListBuffer[Int]
  val pkl = implicitly[Pickler[Lst[Int]]]
  pkl.pickle(buf, xs)
  println(buf)
  val xs1 = pkl.unpickle(buf)
  println(xs1)
  assert(xs1 == xs)
  assert(eq.eql(xs1, xs))

  val pkl2 = implicitly[Pickler[Lst[Lst[Int]]]]
  pkl2.pickle(buf, xss)
  println(buf)
  val xss1 = pkl2.unpickle(buf)
  println(xss1)
  assert(xss == xss1)
  assert(eq2.eql(xss, xss1))

  val p1 = Pair(1, 2)
  val p2 = Pair(1, 2)
  val p3 = Pair(2, 1)
  val eqp = implicitly[Eq[Pair[Int]]]
  assert(eqp.eql(p1, p2))
  assert(!eqp.eql(p2, p3))

  val pklp = implicitly[Pickler[Pair[Int]]]
  pklp.pickle(buf, p1)
  println(buf)
  val p1a = pklp.unpickle(buf)
  println(p1a)
  assert(p1 == p1a)
  assert(eqp.eql(p1, p1a))

  def showPrintln[T: Show](x: T): Unit =
    println(implicitly[Show[T]].show(x))
  showPrintln(xs)
  showPrintln(xss)

  val zs = Lst.Cons(Left(1), Lst.Cons(Right(Pair(2, 3)), Lst.Nil))
  showPrintln(zs)

  def pickle[T: Pickler](buf: mutable.ListBuffer[Int], x: T): Unit =
    implicitly[Pickler[T]].pickle(buf, x)

  def unpickle[T: Pickler](buf: mutable.ListBuffer[Int]): T =
    implicitly[Pickler[T]].unpickle(buf)

  def copy[T: Pickler](x: T): T = {
    val buf = new mutable.ListBuffer[Int]
    pickle(buf, x)
    unpickle[T](buf)
  }

  def eql[T: Eq](x: T, y: T) = implicitly[Eq[T]].eql(x, y)

  val zs1 = copy(zs)
  showPrintln(zs1)
  assert(eql(zs, zs1))
}
*/