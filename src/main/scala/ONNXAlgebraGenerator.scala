import scala.meta._
import java.nio.file.Files
import java.nio.file.Paths

object ONNXAlgebraGenerator extends App {

  val path = Paths.get("ONNXAlgebra.scala");

  val attrMap = Array("Float",
                      "Int",
                      "String",
                      "Tensor",
                      "GRAPH???",
                      "Seq[Float]",
                      "Seq[Int]",
                      "Seq[String]",
                      "Seq[Tensor]",
                      "Seq[GRAPH???]").zipWithIndex.toMap
  val attrTypeMap = for ((k, v) <- attrMap) yield (v, k)

  org.bytedeco.javacpp.Loader.load(classOf[org.bytedeco.javacpp.onnx])

  val schemas = org.bytedeco.javacpp.onnx.OpSchemaRegistry.get_all_schemas
  val schemasSize = schemas.size
  val scalaCollSchemas = (0 until schemasSize.toInt).map(x => schemas.get(x))
  val tuples = scalaCollSchemas.map(
    x =>
      (x.Name.getString,
       x.since_version,
       x.inputs,
       x.outputs,
       x.typeConstraintParams,
       x.attributes))

  val traitStringsAndTypeStrings = tuples.map { x =>
    val typeConstraintParams = x._5

    val typeStringMap = (0 until x._5.size.toInt)
      .map { y =>
        val typeConstraintParam = typeConstraintParams.get(y)
        val allowedTypeStrings =
          (0 until typeConstraintParam.allowed_type_strs.size.toInt).map(
            z =>
              typeConstraintParam.allowed_type_strs
                .get(z)
                .getString
                .substring(7)
                .capitalize
                .dropRight(1))
        allowedTypeStrings.map(z =>
          (typeConstraintParam.type_param_str.getString + z -> (typeConstraintParam.type_param_str.getString, "  type " + typeConstraintParam.type_param_str.getString + z + " = Tensor[" + z
            .replaceAll("Int32", "Int")
            .replaceAll("Int64", "Long")
            .replaceAll("Bool", "Boolean") + "]" + "\n")))
      }
      .flatten
      .toMap

    //CAUTION: iterator, unsafe
    val attrIter = x._6.begin
    val attributesString: String = (0 until x._6.size.toInt)
      .map { y =>
        val result =
          (attrIter.first.getString, attrTypeMap(attrIter.second.`type`))
        val required = attrIter.second.required
        attrIter.increment
        val str = "" + result._1
          .replaceAll("split", "splitAttr")
          .replaceAll("scale", "scaleAttr") + " : " + (if (required) ""
                                                       else
                                                         "Option[") + "(" + result._2
          .replaceAll("Tensor", "example.Tensor[Number]") + ")" + (if (required)
                                                                     ""
                                                                   else
                                                                     "] = None")
        str
      }
      .mkString(",")

    val requiredInputs = (0 until x._3.size.toInt)
      .map(y => x._3.get(y))
      .filter(y => y.GetOption == 0)
    val optionalInputs = (0 until x._3.size.toInt)
      .map(y => x._3.get(y))
      .filter(y => y.GetOption == 1)

    val traitString
      : String = "@free trait " + x._1 + " extends Operator" + " {\n" +
      "\n  def " + x._1 + "(" +
      requiredInputs
        .map(y =>
          y.GetName.getString
            .replaceAll("var", "someVar") + ": " + "example." + y.GetTypeStr.getString
            .replaceAll("tensor\\(int64\\)", "Tensor[Long]"))
        .mkString(", ") +
      (if (requiredInputs.size > 0 && optionalInputs.size > 0) "," else "") +
      optionalInputs
        .map(y =>
          y.GetName.getString
            .replaceAll("var", "someVar")
            .replaceAll("shape", "shapeInput") + ": " + "Option[example." + y.GetTypeStr.getString
            .replaceAll("tensor\\(int64\\)", "Tensor[Long]") + "] = None")
        .mkString(", ") +
      (if (attributesString.size > 0 && (requiredInputs.size + optionalInputs.size) > 0)
         "," + attributesString
       else "") +
      ")\n" + "    : FS[(" + (0 until x._4.size.toInt)
      .map(y => x._4.get(y))
      .map(y =>
        "example." + y.GetTypeStr.getString.replaceAll("tensor\\(int32\\)",
                                                       "Tensor[Int]"))
      .mkString(", ") + ")]\n" +
//       attributesString +
      "\n}"

    (traitString, typeStringMap)
  }

  val flattenedTypeStringsMap =
    traitStringsAndTypeStrings.map(x => x._2).flatten.toMap
  val typeStrings = flattenedTypeStringsMap.values
    .map(z => z._2)
    .mkString("\n") + flattenedTypeStringsMap.values
    .map(z => "  type " + z._1 + " = Tensor[Number]")
    .toList
    .distinct
    .mkString("\n")
  val traitStrings = traitStringsAndTypeStrings
    .map(x => x._1)
    .filter(x => !x.contains("ATen"))
    .mkString("\n")

  val fullSource = "package example\n\n" +
    "import freestyle.free._\n" +
    "import freestyle.free.implicits._\n" +
    "import spire.math.Number\n" +
    "import scala.language.higherKinds\n\n" +
    "package object example {\n" +
    "  type Tensor[T] = Tuple2[Vector[T], Seq[Int]]\n" +
    "  trait Operator\n" +
    typeStrings + "\n" +
    "}\n" +
    "@free trait DataSource {\n" +
    "  def inputData: FS[example.T]\n" +
    "  def getParams(name: String): FS[example.T]\n" +
    "  def getAttributes(name: String): FS[example.T]\n" +
    "}\n" +
    traitStrings

//  val traitSource = traitStrings.parse[Source].get.syntax

  def generate() = {
    val onnxSource = fullSource.parse[Source].get

    Files.write(path, onnxSource.syntax.getBytes("UTF-8"));
  }

  generate()
}
