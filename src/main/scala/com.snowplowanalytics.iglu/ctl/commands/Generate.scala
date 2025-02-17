/*
 * Copyright (c) 2012-2022 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.snowplowanalytics.iglu.ctl
package commands

import java.nio.file.{Path, Paths}
import cats.data._
import cats.effect.IO
import cats.implicits._
import cats.{Monoid, Traverse}
import io.circe._
import com.snowplowanalytics.iglu.core.{SchemaKey, SelfDescribingSchema}
import com.snowplowanalytics.iglu.ctl.Command.StaticGenerate
import com.snowplowanalytics.iglu.schemaddl.redshift._
import com.snowplowanalytics.iglu.schemaddl.jsonschema.Schema
import com.snowplowanalytics.iglu.schemaddl.jsonschema.circe.implicits._
import com.snowplowanalytics.iglu.ctl.Common.Error
import com.snowplowanalytics.iglu.schemaddl.StringUtils.snakeCase

import scala.math.Ordered.orderingToOrdered

object Generate {

  implicit val ord: Ordering[SchemaKey] = SchemaKey.ordering

  type IgluSchema = SelfDescribingSchema[Schema]

  /**
    * Primary working method of `generate` command
    * Get all JSONs from specified path, try to parse them as JSON Schemas,
    * convert to [[DdlOutput]] (combined table definition, JSON Paths, etc)
    * and output them to specified path, also output errors
    */
  def process(command: StaticGenerate): Result = {

    val output = getOutput(command.output)

    for {
      _ <- File.checkOutput(output)
      schemaFiles <- EitherT(File.readSchemas(command.input).map(Common.leftBiasedIor))
      igluSchemas = parseSchemas(schemaFiles.map(_.content)).leftMap(NonEmptyList.one)
      schemas <- EitherT.fromEither[IO](igluSchemas)
      result = transform(command.dbSchema, command.usePostgres, schemas)
      messages <- EitherT(outputResult(output, result, command.force))
    } yield messages
  }

  /**
    * Gives path which executable is called if given
    * path is None
    */
  def getOutput(output: Option[Path]): Path =
    output.getOrElse(Paths.get("").toAbsolutePath)

  /**
    * Create Schema objects from list of schema jsons
    */
  def parseSchemas[F[_] : Traverse](schemas: F[SelfDescribingSchema[Json]]): Either[Common.Error, F[IgluSchema]] =
    schemas.traverse[Option, IgluSchema] { s =>
      Schema.parse(s.schema).map(e => SelfDescribingSchema(s.self, e))
    }.toRight(Common.Error.Message("Error while parsing schema jsons to Schema object"))


  def createSchemaSql(schemaName: String): String = s"CREATE SCHEMA IF NOT EXISTS $schemaName;" 


  def redshiftToPostgresSql(sql: String): String = {
    sql
      .replaceAll(" ENCODE ZSTD", "")
      .replaceAll(" ENCODE RAW", "")
      .replaceAll(" ENCODE RUNLENGTH", "")
      .replaceAll("DISTSTYLE KEY", "")
      .replaceAll("DISTKEY \\([^)]+\\)", "")
      .replaceAll("SORTKEY \\([^)]+\\)", "")
      .replaceAll("\\sCHAR\\(36\\)", " UUID   ")  // Replace CHAR(36) with UUID
      .replaceAll("DOUBLE PRECISION", "FLOAT8")  // Replace DOUBLE PRECISION with FLOAT8

      .replaceAll("BYTEINT", "SMALLINT")  // Convert BYTEINT to SMALLINT
      .replaceAll("VARCHAR\\(MAX\\)", "TEXT")  // Convert VARCHAR(MAX) to TEXT
      .replaceAll("VARBINARY\\(MAX\\)", "BYTEA")  // Convert VARBINARY(MAX) to BYTEA
      .replaceAll("DATETIME", "TIMESTAMP")  // Convert DATETIME to TIMESTAMP
      .replaceAll("TINYINT", "SMALLINT")  // Convert TINYINT to SMALLINT

      // Remove Redshift loader specific columns
      .replaceAll("(?i)\\s*\"ref_(root|tree|parent)\"[^\n]*", "")
      // Remove FOREIGN KEY due to lack of uniqueness constraint on event_id
      .replaceAll(",\\s*\n\\s*FOREIGN KEY \\(root_id\\) REFERENCES[^\n]*\n", "\n")
      .trim
  }

  /**
    * Class holding an aggregated output ready to be written
    * with all warnings collected due transformations
    *
    * @param ddls       list of files with table definitions
    * @param migrations list of files with available migrations
    * @param warnings   all warnings collected in process of parsing and
    *                   transformation
    */
  case class DdlOutput(ddls: List[File[String]], migrations: List[File[String]], warnings: List[String])

  implicit val ddlOutputMonoid: Monoid[DdlOutput] = new Monoid[DdlOutput] {

    override def empty: DdlOutput = DdlOutput(Nil, Nil, Nil)

    override def combine(x: DdlOutput, y: DdlOutput): DdlOutput = DdlOutput(x.ddls ++ y.ddls, x.migrations ++ y.migrations, x.warnings ++ y.warnings)

  }

  /** Dump warnings to stdout and collect errors and info messages for main method */
  def outputResult(output: Path, result: DdlOutput, force: Boolean): IO[EitherNel[Error, List[String]]] =
    for {
      _ <- result.warnings.traverse_(printWarning)
      ddls = result.ddls.map(_.setBasePath("sql")).map(_.setBasePath(output.toFile.getAbsolutePath))
      ddlsMessages <- ddls.traverse[IO, Either[Error, String]](file => file.write(force))
      migrations = result.migrations.map(_.setBasePath("sql")).map(_.setBasePath(output.toFile.getAbsolutePath))
      migrationsMessages <- migrations.traverse[IO, Either[Error, String]](file => file.write(force))
    } yield (ddlsMessages ++ migrationsMessages).parTraverse(_.toEitherNel)

  /** Log message either to stderr or stdout */
  private def printWarning(warning: String): IO[Unit] =
    IO(System.out.println(warning))


  /**
    * Transform list of self-describing JSON Schemas to a single [[DdlOutput]] containing
    * all data to produce: DDL files, migrations, etc
    */
  private[ctl] def transform(dbSchema: String, usePostgres: Boolean, schemas: NonEmptyList[IgluSchema]): DdlOutput =
    schemas.groupByNem { s => (
      s.self.schemaKey.name,
      s.self.schemaKey.vendor,
      s.self.schemaKey.version.model,
    )}.toSortedMap
      .values
      .toList
      .map { s =>
        val lookup: collection.Map[SchemaKey, ShredModel] = foldMapMergeRedshiftSchemas(s)
        val model = getFinalMergedModel(s)
        val sortedKeys = lookup.keys.toList.sorted
        val (gaps, _) = sortedKeys.foldLeft((List.empty[String], sortedKeys.head)) {
          case ((acc, lastKey), k) => if (
            ((k.version.revision - lastKey.version.revision > 1) & (k.version.addition == lastKey.version.addition)) |
              (k.version.addition - lastKey.version.addition > 1)) {
            (acc :+ s"Gap in revisions between ${lastKey.toSchemaUri} and ${k.toSchemaUri}", k)
          } else {
            (acc, k)
          }
        }

        val failedMerges = lookup.values.toList.collect {
          case rec: ShredModel.RecoveryModel => rec.errorAsStrings.map { e =>
            s"${rec.schemaKey.toSchemaUri} has a breaking change $e"
          }.toList
        }.flatten
        val ddl = File.textFile(
          tblPath(model),
          createSchemaSql(dbSchema) + "\n\n" +
          (if (usePostgres) redshiftToPostgresSql(model.toTableSql(dbSchema)) else model.toTableSql(dbSchema))
              .replaceAll("""\s+$""", "")
              .replaceAll(raw"(?m)^  ", "    ")
        )
        val migrations = (sortedKeys, sortedKeys).mapN((l, h) => (l, h)).collect {
          case (low, high) if high > low =>
            val migrationSql = if (usePostgres) redshiftToPostgresSql(model.migrationSql(dbSchema, Some(low), Some(high))) else model.migrationSql(dbSchema, Some(low), Some(high))
File.textFile(migrationPath(model, low, high), migrationSql)
        }
        DdlOutput(List(ddl), migrations, gaps ++ failedMerges)
      }.combineAll

  def migrationPath(model: ShredModel, from: SchemaKey, to: SchemaKey): Path = Paths.get(model.schemaKey.vendor.toLowerCase, model.schemaKey.name.toLowerCase, from.version.asString, to.version.asString + ".sql")
  
  def tblPath(model: ShredModel): Path = Paths.get(model.schemaKey.vendor.toLowerCase, snakeCase(model.schemaKey.name) + "_" + model.schemaKey.version.model + ".sql")
}
