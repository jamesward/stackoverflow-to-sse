package controllers

import akka.stream.scaladsl.{Flow, Sink, Source}
import com.google.cloud.bigquery.{FieldValueList, LegacySQLTypeName, Schema}
import javax.inject.{Inject, Singleton}
import play.api.Logging
import play.api.http.ContentTypes
import play.api.libs.EventSource
import play.api.libs.json._
import play.api.mvc.InjectedController

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.Try

@Singleton
class Application @Inject() extends InjectedController with Logging {

  def index = Action { implicit request =>
    Ok(views.html.index())
  }

  implicit val fieldValueListWrites = new Writes[(Schema, FieldValueList)] {
    override def writes(o: (Schema, FieldValueList)): JsValue = {
      val (schema, fieldValueList) = o

      schema.getFields.asScala.foldLeft(JsObject.empty) { case (json, field) =>
        val fieldValue = fieldValueList.get(field.getName)
        val fieldValueJson = field.getType match {
          case LegacySQLTypeName.BOOLEAN => Try(fieldValue.getBooleanValue).map(JsBoolean).getOrElse(JsNull)
          case LegacySQLTypeName.STRING => Try(fieldValue.getStringValue).map(JsString).getOrElse(JsNull)
          case LegacySQLTypeName.INTEGER => Try(fieldValue.getLongValue).map(JsNumber(_)).getOrElse(JsNull)
          case _ => JsNull
        }

        json + (field.getName -> fieldValueJson)
      }
    }
  }

  def tagsToArray(jsValue: JsValue): JsValue = {
    jsValue.transform(
      (__ \ "tags").json.update(
        __.read[String].map { tags =>
          JsArray(
            tags.split("><").map { tag =>
              JsString(tag.stripPrefix("<").stripSuffix(">"))
            }
          )
        }
      )
    ).getOrElse(jsValue)
  }

  def questions = Action {
    val query = """
                  |SELECT CONCAT('https://stackoverflow.com/questions/', CAST(id as STRING)) as url, title, body, tags, view_count, favorite_count
                  |FROM `bigquery-public-data.stackoverflow.posts_questions`
                  |ORDER BY favorite_count DESC
                  |LIMIT 100000
                  |""".stripMargin

    val source = StackOverflowBigQuery.query(query).fold({ t =>
      logger.error("Query error", t)
      Source.failed(t)
    }, { case (schema, questions) =>
      val questionSource = Source.fromIterator(() => questions.iterator)
      Source.tick(Duration.Zero, 1.second, schema).zip(questionSource).map(Json.toJson(_)).map(tagsToArray)
    })

    Ok.chunked(source via EventSource.flow).as(ContentTypes.EVENT_STREAM)
  }

}

object StackOverflowBigQuery {
  import java.util.UUID

  import com.google.cloud.bigquery.{BigQuery, BigQueryOptions, FieldValueList, JobId, JobInfo, QueryJobConfiguration}

  def query(query: String): Try[(Schema, Iterable[FieldValueList])] = {
    Try {
      val bigQuery = BigQueryOptions.getDefaultInstance.getService
      val jobId = JobId.of(UUID.randomUUID().toString)
      val queryConfig = QueryJobConfiguration.newBuilder(query).setUseLegacySql(false).build()
      val queryJob = bigQuery.create(JobInfo.newBuilder(queryConfig).setJobId(jobId).build())
      queryJob.waitFor()
      val page = queryJob.getQueryResults(BigQuery.QueryResultsOption.pageSize(1))
      page.getSchema -> page.iterateAll().asScala
    }
  }

}
