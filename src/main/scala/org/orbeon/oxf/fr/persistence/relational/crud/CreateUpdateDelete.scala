/**
 * Copyright (C) 2013 Orbeon, Inc.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version
 * 2.1 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.fr.persistence.relational.crud

import java.io.{ByteArrayOutputStream, InputStream, Writer}
import java.sql.{Array ⇒ _, _}
import javax.xml.transform.OutputKeys
import javax.xml.transform.sax.{SAXResult, SAXSource}
import javax.xml.transform.stream.StreamResult

import org.orbeon.oxf.fr.FormRunner.{XF, XH}
import org.orbeon.oxf.fr.persistence.relational.Version._
import org.orbeon.oxf.fr.persistence.relational.index.Index
import org.orbeon.oxf.fr.persistence.relational.{ForDocument, Specific, _}
import org.orbeon.oxf.pipeline.api.PipelineContext
import org.orbeon.oxf.processor.generator.RequestGenerator
import org.orbeon.oxf.util.ScalaUtils._
import org.orbeon.oxf.util.{NetUtils, StringBuilderWriter, Whitespace, XPath}
import org.orbeon.oxf.webapp.HttpStatusCodeException
import org.orbeon.oxf.xml.{JXQName, _}
import org.orbeon.saxon.event.SaxonOutputKeys
import org.orbeon.saxon.om.DocumentInfo
import org.orbeon.scaxon.SAXEvents.{Atts, StartElement}
import org.xml.sax.InputSource

object RequestReader {

  object IdAtt {
    val IdQName = JXQName("id")
    def unapply(atts: Atts) = atts.atts collectFirst { case (IdQName, value) ⇒ value }
  }

  // See https://github.com/orbeon/orbeon-forms/issues/2385
  private val MetadataElementsToKeep = Set(
    "metadata",
    "title",
    "permissions",
    "available"
  )

  // NOTE: Tested that the pattern match works optimally: with form-with-metadata.xhtml, JQName.unapply is
  // called 17 times and IdAtt.unapply 2 times until the match is found, which is what is expected.
  def isMetadataElement(stack: List[StartElement]): Boolean =
    stack match {
      case
        StartElement(JXQName("", "metadata"), _)                         ::
        StartElement(JXQName(XF, "instance"), IdAtt("fr-form-metadata")) ::
        StartElement(JXQName(XF, "model"),    IdAtt("fr-form-model"))    ::
        StartElement(JXQName(XH, "head"), _)                             ::
        StartElement(JXQName(XH, "html"), _)                             ::
        Nil ⇒ true
      case _  ⇒ false
    }

  def requestInputStream(): InputStream =
    RequestGenerator.getRequestBody(PipelineContext.get) match {
      case bodyURL: String ⇒ NetUtils.uriToInputStream(bodyURL)
      case _               ⇒ NetUtils.getExternalContext.getRequest.getInputStream
    }

  def bytes(): Array[Byte] = {
    val os = new ByteArrayOutputStream
    NetUtils.copyStream(requestInputStream(), os)
    os.toByteArray
  }

  def dataAndMetadataAsString(metadata: Boolean): (String, Option[String]) =
    dataAndMetadataAsString(requestInputStream(), metadata)

  def dataAndMetadataAsString(inputStream: InputStream, metadata: Boolean): (String, Option[String]) = {

    def newTransformer = (
      TransformerUtils.getXMLIdentityTransformer
      |!> (_.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes"))
    )

    def newIdentityReceiver(writer: Writer) = (
      TransformerUtils.getIdentityTransformerHandler
      |!> (_.getTransformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes"))
      |!> (_.getTransformer.setOutputProperty(OutputKeys.INDENT, "no"))
      |!> (_.getTransformer.setOutputProperty(SaxonOutputKeys.INCLUDE_CONTENT_TYPE, "no"))
      |!> (_.setResult(new StreamResult(writer)))
    )

    val metadataWriterAndReceiver = metadata option {

      val metadataWriter = new StringBuilderWriter()

      // MAYBE: strip enclosing namespaces; truncate long titles
      val metadataFilter =
        new FilterReceiver(
          new WhitespaceXMLReceiver(
            new ElementFilterXMLReceiver(
              newIdentityReceiver(metadataWriter),
              (level, uri, localname) ⇒ level != 1 || level == 1 && uri == "" && MetadataElementsToKeep(localname)
            ),
            Whitespace.Normalize,
            (_, _, _, _) ⇒ Whitespace.Normalize
          ),
          isMetadataElement
        )

      (metadataWriter, metadataFilter)
    }

    val source     = new SAXSource(XMLParsing.newXMLReader(XMLParsing.ParserConfiguration.PLAIN), new InputSource(inputStream))
    val dataWriter = new StringBuilderWriter()

    val resultReceiver = metadataWriterAndReceiver match {
      case Some((_, metadataFilter)) ⇒
        new TeeXMLReceiver(newIdentityReceiver(dataWriter), metadataFilter)
      case None ⇒
        newIdentityReceiver(dataWriter)
    }

    newTransformer.transform(source, new SAXResult(resultReceiver))

    (dataWriter.toString, metadataWriterAndReceiver map (_._1.toString))
  }

  // Used by FlatView
  def xmlDocument(): DocumentInfo =
    TransformerUtils.readTinyTree(XPath.GlobalConfiguration, requestInputStream(), "", false, false)
}

trait CreateUpdateDelete
  extends RequestResponse
    with Common
  with CreateCols {

  private def existingRow(connection: Connection, req: Request): Option[Row] = {

    val idCols = idColumns(req)
    val table  = tableName(req)
    val resultSet = {
      val ps = connection.prepareStatement(
        s"""|SELECT created
          |       ${if (req.forData) ", username , groupname, form_version" else ""}
          |FROM   $table t,
          |       (
          |           SELECT   max(last_modified_time) last_modified_time, ${idCols.mkString(", ")}
          |           FROM     $table
          |           WHERE    app  = ?
          |                    and form = ?
          |                    ${if (! req.forData)     "and form_version = ?" else ""}
          |                    ${if (req.forData)       "and document_id  = ?" else ""}
          |                    ${if (req.forAttachment) "and file_name    = ?" else ""}
          |           GROUP BY ${idCols.mkString(", ")}
          |       ) m
          |WHERE  ${joinColumns("last_modified_time" +: idCols, "t", "m")}
          |       AND deleted = 'N'
          |""".stripMargin)
      val position = Iterator.from(1)
      ps.setString(position.next(), req.app)
      ps.setString(position.next(), req.form)
      if (! req.forData)     ps.setInt   (position.next(), requestedFormVersion(connection, req))
      if (req.forData)       ps.setString(position.next(), req.dataPart.get.documentId)
      if (req.forAttachment) ps.setString(position.next(), req.filename.get)
      ps.executeQuery()
    }

    // Create Row object with first row of result
    if (resultSet.next()) {
      val row = Row(resultSet.getTimestamp("created"),
                if (req.forData) Option(resultSet.getString("username" )) else None,
                if (req.forData) Option(resultSet.getString("groupname")) else None,
                if (req.forData) Option(resultSet.getInt("form_version")) else None)
      // The query could return multiple rows if we have both a draft and non-draft, but the `created`,
      // `username`, `groupname`, and `form_version` must be the same on all rows, so it doesn't matter from
      // which row we read this from.
      Some(row)
    } else {
      None
    }
  }

  private def store(connection: Connection, req: Request, existingRow: Option[Row], delete: Boolean): Int = {

    val table = tableName(req)
    val versionToSet = existingRow.flatMap(_.formVersion).getOrElse(requestedFormVersion(connection, req))

    // If we saved data, delete any draft document and draft attachments
    if (req.forData && ! req.forAttachment) {

      // First delete from orbeon_i_control_text, which requires a join
      connection.prepareStatement(
        s"""|DELETE FROM orbeon_i_control_text
            |WHERE data_id IN (
            |    SELECT data_id
            |    FROM   orbeon_i_current
            |    WHERE  document_id = ?   AND
            |           draft       = 'Y'
            |)
            |""".stripMargin)
        .kestrel(_.setString(1, req.dataPart.get.documentId))
        .kestrel(_.executeUpdate())

      // Then delete from all the other tables
      val tablesToDeleteDraftsFrom = List(
        "orbeon_i_current",
        "orbeon_form_data",
        "orbeon_form_data_attach"
      )
      tablesToDeleteDraftsFrom.foreach(table ⇒
        connection.prepareStatement(
          s"""|DELETE FROM $table
              |WHERE  document_id = ?   AND
              |       draft       = 'Y'
              |""".stripMargin)
          .kestrel(_.setString(1, req.dataPart.get.documentId))
          .kestrel(_.executeUpdate())
      )
    }

    // Do insert
    locally {

      val possibleCols = insertCols(req, existingRow, delete, versionToSet)
      val includedCols = possibleCols.filter(_.included)
      val colNames     = includedCols.map(_.name).mkString(", ")
      val colValues    =
        includedCols
          .map(_.value match {
            case StaticColValue(value)           ⇒ value
            case DynamicColValue(placeholder, _) ⇒ placeholder})
          .mkString(", ")

      val ps = connection.prepareStatement(
        s"""|INSERT INTO $table
          |            ( $colNames  )
          |     VALUES ( $colValues )
          |""".stripMargin)

      // Set parameters in prepared statement for the dynamic values
      includedCols
        .map(_.value)
        .collect({ case DynamicColValue(_, paramSetter) ⇒ paramSetter })
        .zipWithIndex
        .foreach{ case (paramSetter, index) ⇒ paramSetter(ps, index + 1)}

      ps.executeUpdate()
    }

    versionToSet
  }
  
  def change(req: Request, delete: Boolean): Unit = {

    // Read before establishing a connection, so we don't use two simultaneous connections
    val formPermissions = req.forData.option(RelationalUtils.readFormPermissions(req.app, req.form)).flatten

    RelationalUtils.withConnection { connection ⇒

      // Initial test on version that doesn't rely on accessing the database to read a document; we do this first:
      // - For correctness: e.g., a PUT for a document id is an invalid request, but if we start by checking
      //   permissions, we might not find the document and return a 400 instead.
      // - For efficiency: when we can, it's better to 400 right away without accessing the database.
      def checkVersionInitial(): Unit = {
        val badVersion =
          // Only GET for form definitions can request a version for a given document
          req.version.isInstanceOf[ForDocument] ||
          // Delete: no version can be specified
          req.forData && delete && ! (req.version == Unspecified)
        if (badVersion) throw HttpStatusCodeException(400)
      }

      def checkAuthorized(existing: Option[Row]): Unit = {
        val authorized =
          if (req.forData) {
            if (existing.isDefined) {
              // Check we're allowed to update or delete this resource
              val username      = existing.get.username
              val groupname     = existing.get.group
              val authorizedOps = RelationalUtils.allAuthorizedOperations(formPermissions, username → groupname)
              val requiredOp    = if (delete) "delete" else "update"
              authorizedOps.contains(requiredOp)
            } else {
              // For deletes, if there is no data to delete, it is a 403 if could not read, update,
              // or delete if it existed (otherwise code later will return a 404)
              val authorizedOps = RelationalUtils.allAuthorizedOperations(formPermissions, None → None)
              val requiredOps   = if (delete) Set("read", "update", "delete") else Set("create")
              authorizedOps.intersect(requiredOps).nonEmpty
            }
          } else {
            // Operations on deployed forms are always authorized
            true
          }
        if (! authorized) throw HttpStatusCodeException(403)
      }

      def checkVersionWithExisting(existing: Option[Row]): Unit = {

        def isUpdate =
          ! delete && existing.nonEmpty

        def isCreate =
          ! delete && existing.isEmpty

        def existingVersionOpt =
          existing flatMap (_.formVersion)

        def isUnspecifiedOrSpecificVersion =
          req.version match {
            case Unspecified       ⇒ true
            case Specific(version) ⇒ existingVersionOpt.contains(version)
            case _                 ⇒ false
          }

        def isSpecificVersion =
          req.version.isInstanceOf[Specific]

        def badVersion =
          (req.forData && isUpdate && ! isUnspecifiedOrSpecificVersion) ||
          (req.forData && isCreate && ! isSpecificVersion)

        if (badVersion)
          throw HttpStatusCodeException(400)
      }

      def checkDocExistsForDelete(existing: Option[Row]): Unit = {
        // We can't delete a document that doesn't exist
        val nothingToDelete = delete && existing.isEmpty
        if (nothingToDelete) throw HttpStatusCodeException(404)
      }

      // Checks
      checkVersionInitial()
      val existing = existingRow(connection, req)
      checkAuthorized(existing)
      checkVersionWithExisting(existing)
      checkDocExistsForDelete(existing)

      // Update database
      val versionSet = store(connection, req, existing, delete)

      // Update index
      val whatToReindex = req.dataPart match {
          case Some(dataPart) ⇒
            // Data: update index for this document id
            Index.DataForDocumentId(dataPart.documentId)
          case None ⇒
            // Form definition: update index for this form version
            // Re. the asInstanceOf, when updating a form, we must have a specific version specified
            Index.DataForForm(req.app, req.form, versionSet)
        }
      Index.reindex(req.provider, connection, whatToReindex)

      // Create flat view if needed
      if (requestFlatView && FlatView.SupportedProviders(req.provider) && req.forForm && ! req.forAttachment && ! delete && req.form != "library")
        FlatView.createFlatView(req, connection)

      // Inform caller of the form definition version used
      httpResponse.setHeader(OrbeonFormDefinitionVersion, versionSet.toString)

      httpResponse.setStatus(if (delete) 204 else 201)
    }
  }
}
