package org.thp.cortex.models

import javax.inject.{ Inject, Provider, Singleton }

import scala.concurrent.{ ExecutionContext, Future }

import play.api.Logger
import play.api.libs.json.{ JsNumber, JsObject, JsString, Json }

import org.elastic4play.models.JsonFormat.enumFormat
import org.elastic4play.models.{ AttributeDef, BaseEntity, EntityDef, HiveEnumeration, ModelDef, AttributeFormat ⇒ F, AttributeOption ⇒ O }
import org.elastic4play.services.FindSrv

object OrganizationStatus extends Enumeration with HiveEnumeration {
  type Type = Value
  val Active, Locked = Value
  implicit val reads = enumFormat(this)
}

trait OrganizationAttributes { _: AttributeDef ⇒
  val name = attribute("name", F.stringFmt, "Organization name", O.form)
  val _id = attribute("_id", F.stringFmt, "Organization name", O.model)
  val description = attribute("description", F.stringFmt, "Organization description")
  val status = attribute("status", F.enumFmt(OrganizationStatus), "Status of the organization", OrganizationStatus.Active)
}

@Singleton
class OrganizationModel @Inject() (
    findSrv: FindSrv,
    userModelProvider: Provider[UserModel],
    analyzerModelProvider: Provider[AnalyzerModel],
    implicit val ec: ExecutionContext) extends ModelDef[OrganizationModel, Organization]("organization", "Organization", "/organization") with OrganizationAttributes with AuditedModel {

  private lazy val logger = Logger(getClass)
  lazy val userModel = userModelProvider.get
  lazy val analyzerModel = analyzerModelProvider.get
  override def removeAttribute = Json.obj("status" -> "Locked")

  override def creationHook(parent: Option[BaseEntity], attrs: JsObject): Future[JsObject] =
    Future.successful {
      (attrs \ "name").asOpt[JsString].fold(attrs) { orgName ⇒
        attrs - "name" + ("_id" → orgName)
      }
    }

  private def buildUserStats(organization: Organization): Future[JsObject] = {
    import org.elastic4play.services.QueryDSL._
    findSrv(
      userModel,
      "organization" ~= organization.id,
      groupByField("status", selectCount))
      .map { userStatsJson ⇒
        val (userCount, userStats) = userStatsJson.value.foldLeft((0L, JsObject.empty)) {
          case ((total, s), (key, value)) ⇒
            val count = (value \ "count").as[Long]
            (total + count, s + (key → JsNumber(count)))
        }
        Json.obj("users" → (userStats + ("total" → JsNumber(userCount))))
      }
  }

  private def buildAnalyzerStats(organization: Organization): Future[JsObject] = {
    import org.elastic4play.services.QueryDSL._
    findSrv(
      analyzerModel,
      withParent(organization),
      groupByField("status", selectCount))
      .map { analyzerStatsJson ⇒
        val (analyzerCount, analyzerStats) = analyzerStatsJson.value.foldLeft((0L, JsObject.empty)) {
          case ((total, s), (key, value)) ⇒
            val count = (value \ "count").as[Long]
            (total + count, s + (key → JsNumber(count)))
        }
        Json.obj("analyzers" → (analyzerStats + ("total" → JsNumber(analyzerCount))))
      }
  }

  override def getStats(entity: BaseEntity): Future[JsObject] = {
    entity match {
      case organization: Organization ⇒
        for {
          userStats ← buildUserStats(organization)
          analyzerStats ← buildAnalyzerStats(organization)
        } yield userStats ++ analyzerStats
      case other ⇒
        logger.warn(s"Request caseStats from a non-case entity ?! ${other.getClass}:$other")
        Future.successful(Json.obj())
    }
  }

}

class Organization(model: OrganizationModel, attributes: JsObject) extends EntityDef[OrganizationModel, Organization](model, attributes) with OrganizationAttributes {
  override def toJson: JsObject = super.toJson + ("name" -> JsString(id))
}