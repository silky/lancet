package lancet.interpreter

import com.oracle.graal.api._;
import com.oracle.graal.api.meta._;
import com.oracle.graal.hotspot.meta._;
import com.oracle.graal.bytecode._;

import scala.collection.{mutable,immutable}


// version 0
// incomplete: bail out if a block has been seen 3 times

class BytecodeInterpreter_Opt0 extends BytecodeInterpreter_Str with RuntimeUniverse_Opt {
    override def getRuntimeInterface(m: MetaAccessProvider) = new Runtime_Opt(m)
    override def objectGetClass(receiver: Rep[Object]): Option[Class[_]] = {
      eval(receiver) match {
        case Partial(fs) => 
          val Const(clazz: Class[_]) = eval(fs("clazz"))
          Some(clazz)
        case Const(x) =>
          val clazz = x.getClass
          Some(clazz)
        case _ =>
          None
      }        
    }

    var worklist: IndexedSeq[InterpreterFrame] = Vector.empty

    var budget = 50000

    var emitControlFlow = true
    var emitRecursive = false

    def exec(frame: InterpreterFrame): Rep[Unit] = { // called internally to initiate control transfer
      
      if (budget <= 0) {
        println("// *** BUDGET EXCEEDED ***")
        return unit(().asInstanceOf[Object]).asInstanceOf[Rep[Unit]]
      }

      if (frame.getParentFrame == null) { // TODO: cleanup?
        val p = popAsObject(frame, frame.getMethod.signature.returnKind())
        return reflect[Unit]("(RES = "+p+") // return to root")
      }

      val method = frame.getMethod()
      if (!emitRecursive && getContext(frame).drop(1).exists(_.getMethod() == method)) { // recursive (TODO: faster test)
        println("// *** RECURSIVE: "+method+" ***")
        return reflect[Unit]("throw new Exception(\"RECURSIVE: "+frame.getMethod+"\")")
      }

      budget -= 1
      
      // decision to make: explore block afresh or generate call to existing onep

      worklist = worklist :+ (frame.asInstanceOf[InterpreterFrame_Str].copy)

      if (emitControlFlow && worklist.tail.nonEmpty)
        reflect[Unit]("goto "+contextKey(frame))
      else
        unit(().asInstanceOf[Object]).asInstanceOf[Rep[Unit]]
    }


    // TODO: can't translate blocks just like that to Scala methods: 
    // next block may refer to stuff defined in earlier block (need nesting, 
    // but problem with back edges)

    // may need different treatment for intra-procedural blocks and function
    // calls: want to inline functions but not generally duplicate local blocks

    def loop(root: InterpreterFrame, main: InterpreterFrame): Unit = {// throws Throwable {

      pushAsObjectInternal(root, main.getMethod.signature().returnKind(), Dyn[Object]("null /* stub return value "+main.getMethod.signature().returnKind()+" */")); // TODO: cleanup?

      val info = new scala.collection.mutable.HashMap[String, Int]

      while (worklist.nonEmpty) {
        var frame = worklist.head
        worklist = worklist.tail

        val key = contextKey(frame)
        val seen = info.getOrElse(contextKey(frame), 0)

        info(key) = seen + 1

        if (seen > 0) {
          println("// *** SEEN " + seen + ": " + key)
        }

        val seenEnough = seen > 3  // TODO: this is just a random cutoff, need to do fixpoint iteration

        if (!seenEnough && frame.getParentFrame != null) {
          val bci = frame.getBCI()
          val bs = new BytecodeStream(frame.getMethod.code())
          //bs.setBCI(globalFrame.getBCI())

          //def frameStr(frame: InterpreterFrame) = getContext(frame).map(frame => ("" + frame.getBCI + ":" + frame.getMethod() + frame.getMethod().signature().asString()).replace("HotSpotMethod",""))

          if (emitControlFlow) {
            println("// *** begin block " + key)
            //println("// *** stack " + frame.asInstanceOf[InterpreterFrame_Str].locals.mkString(","))
          }
          executeBlock(frame, bs, bci)
        } else {
          if (seenEnough) println("// *** seen enough")
        }
      }
    }
}

