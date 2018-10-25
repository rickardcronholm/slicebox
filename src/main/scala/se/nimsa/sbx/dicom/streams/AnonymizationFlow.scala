/*
 * Copyright 2014 Lars Edenbrandt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package se.nimsa.sbx.dicom.streams

import akka.NotUsed
import akka.stream.scaladsl.Flow
import akka.util.ByteString
import com.typesafe.config.{Config, ConfigFactory}
import se.nimsa.dicom.streams.DicomFlows._
import se.nimsa.dicom.data.DicomParsing.isPrivate
import se.nimsa.dicom.data.DicomParts.DicomPart
import se.nimsa.dicom.streams.ModifyFlow.{TagModification, modifyFlow}
import se.nimsa.dicom.data.{Tag, TagPath, VR}
import se.nimsa.sbx.anonymization.AnonymizationUtil._
import se.nimsa.sbx.dicom.DicomUtil._
import se.nimsa.sbx.dicom.streams.DicomStreamUtil._

import scala.collection.JavaConverters._

object AnonymizationFlow {

  private def hex2int (hex: Seq[String]): Seq[Int] = hex.map { h => Integer.parseInt(h, 16) }


  private def insert(tag: Int, mod: ByteString => ByteString) = TagModification.endsWith(TagPath.fromTag(tag), mod, insert = true)
  private def maybe_insert(tag: Int, do_insert: Boolean, mod: ByteString => ByteString) :TagModification = {
    if (do_insert)
      TagModification.endsWith(TagPath.fromTag(tag), mod, insert = true)
    else {
      val dummytag = 196609
      TagModification.endsWith(TagPath.fromTag(dummytag), mod, insert = false)
    }
  }
  private def modify(tag: Int, mod: ByteString => ByteString) = TagModification.endsWith(TagPath.fromTag(tag), mod, insert = false)
  private def maybe_modify(tag: Int, do_insert: Boolean, mod: ByteString => ByteString) = {
    if (do_insert)
      TagModification.endsWith(TagPath.fromTag(tag), mod, insert = false)
    else {
      val dummytag = 196609
      TagModification.endsWith(TagPath.fromTag(dummytag), mod, insert = false)
    }
  }
  private def clear(tag: Int) = TagModification.endsWith(TagPath.fromTag(tag), _ => ByteString.empty, insert = false)

  val appConfig: Config  = ConfigFactory.load()
  val sliceboxConfig: Config = appConfig.getConfig("slicebox")

  private val removeTags = Set(
    Tag.AcquisitionComments,
    Tag.AcquisitionContextSequence,
    Tag.AcquisitionDeviceProcessingDescription,
    Tag.AcquisitionProtocolDescription,
    Tag.ActualHumanPerformersSequence,
    Tag.AdditionalPatientHistory,
    Tag.AddressTrial,
    Tag.AdmissionID,
    Tag.AdmittingDiagnosesCodeSequence,
    Tag.AdmittingDiagnosesDescription,
    Tag.Allergies,
    Tag.Arbitrary,
    Tag.AuthorObserverSequence,
    Tag.BranchOfService,
    Tag.CommentsOnThePerformedProcedureStep,
    Tag.ConfidentialityConstraintOnPatientDataDescription,
    Tag.ContentCreatorIdentificationCodeSequence,
    Tag.ContentSequence,
    Tag.ContributionDescription,
    Tag.CountryOfResidence,
    Tag.CurrentObserverTrial,
    Tag.CurrentPatientLocation,
    Tag.CurveData,
    Tag.CustodialOrganizationSequence,
    Tag.DataSetTrailingPadding,
    Tag.DerivationDescription,
    Tag.DigitalSignatureUID,
    Tag.DigitalSignaturesSequence,
    Tag.DischargeDiagnosisDescription,
    Tag.DistributionAddress,
    Tag.DistributionName,
    Tag.FailedSOPInstanceUIDList,
    Tag.FrameComments,
    Tag.GraphicAnnotationSequence, // type D
    Tag.HumanPerformerName,
    Tag.HumanPerformerOrganization,
    Tag.IconImageSequence,
    Tag.IdentifyingComments,
    Tag.ImageComments,
    Tag.ImagePresentationComments,
    Tag.ImagingServiceRequestComments,
    Tag.Impressions,
    Tag.InstanceCoercionDateTime,
    Tag.InstitutionAddress,
    Tag.InstitutionCodeSequence,
    Tag.InstitutionName,
    Tag.InstitutionalDepartmentName,
    Tag.InsurancePlanIdentification,
    Tag.IntendedRecipientsOfResultsIdentificationSequence,
    Tag.InterpretationApproverSequence,
    Tag.InterpretationAuthor,
    Tag.InterpretationDiagnosisDescription,
    Tag.InterpretationIDIssuer,
    Tag.InterpretationRecorder,
    Tag.InterpretationText,
    Tag.InterpretationTranscriber,
    Tag.IssuerOfAdmissionID,
    Tag.IssuerOfPatientID,
    Tag.IssuerOfServiceEpisodeID,
    Tag.MAC,
    Tag.MedicalAlerts,
    Tag.MedicalRecordLocator,
    Tag.MilitaryRank,
    Tag.ModifiedAttributesSequence,
    Tag.ModifiedImageDescription,
    Tag.ModifyingDeviceID,
    Tag.ModifyingDeviceManufacturer,
    Tag.NameOfPhysiciansReadingStudy,
    Tag.NamesOfIntendedRecipientsOfResults,
    Tag.Occupation,
    Tag.OperatorIdentificationSequence,
    Tag.OperatorsName,
    Tag.OriginalAttributesSequence,
    Tag.OrderCallbackPhoneNumber,
    Tag.OrderEnteredBy,
    Tag.OrderEntererLocation,
    Tag.OtherPatientIDs,
    Tag.OtherPatientIDsSequence,
    Tag.OtherPatientNames,
    Tag.OverlayComments,
    Tag.OverlayData,
    Tag.ParticipantSequence,
    Tag.PatientAddress,
    Tag.PatientComments,
    Tag.PatientState,
    Tag.PatientTransportArrangements,
    Tag.PatientBirthDate,
    Tag.PatientBirthName,
    Tag.PatientBirthTime,
    Tag.PatientInstitutionResidence,
    Tag.PatientInsurancePlanCodeSequence,
    Tag.PatientMotherBirthName,
    Tag.PatientPrimaryLanguageCodeSequence,
    Tag.PatientPrimaryLanguageModifierCodeSequence,
    Tag.PatientReligiousPreference,
    Tag.PatientTelephoneNumbers,
    Tag.PerformedLocation,
    Tag.PerformedProcedureStepDescription,
    Tag.PerformedProcedureStepID,
    Tag.PerformingPhysicianIdentificationSequence,
    Tag.PerformingPhysicianName,
    Tag.PersonAddress,
    Tag.PersonIdentificationCodeSequence, // type D
    Tag.PersonName, // type D
    Tag.PersonTelephoneNumbers,
    Tag.PhysicianApprovingInterpretation,
    Tag.PhysiciansReadingStudyIdentificationSequence,
    Tag.PhysiciansOfRecord,
    Tag.PhysiciansOfRecordIdentificationSequence,
    Tag.PreMedication,
    Tag.ProtocolName,
    Tag.ReasonForTheImagingServiceRequest,
    Tag.ReasonForStudy,
    Tag.ReferencedDigitalSignatureSequence,
    Tag.ReferencedImageSequence, // Keep in UID option but removed here
    Tag.ReferencedPatientAliasSequence,
    Tag.ReferencedPatientPhotoSequence,
    Tag.ReferencedPatientSequence,
    Tag.ReferencedPerformedProcedureStepSequence, // Keep in UID option but removed here
    Tag.ReferencedSOPInstanceMACSequence,
    Tag.ReferencedStudySequence, // Keep in UID option but removed here
    Tag.ReferringPhysicianAddress,
    Tag.ReferringPhysicianIdentificationSequence,
    Tag.ReferringPhysicianTelephoneNumbers,
    Tag.RegionOfResidence,
    Tag.RequestAttributesSequence,
    Tag.RequestedContrastAgent,
    Tag.RequestedProcedureComments,
    Tag.RequestedProcedureDescription,
    Tag.RequestedProcedureID,
    Tag.RequestedProcedureLocation,
    Tag.RequestingPhysician,
    Tag.RequestingService,
    Tag.ResponsibleOrganization,
    Tag.ResponsiblePerson,
    Tag.ResultsComments,
    Tag.ResultsDistributionListSequence,
    Tag.ResultsIDIssuer,
    Tag.ReviewerName,
    Tag.ScheduledHumanPerformersSequence,
    Tag.ScheduledPatientInstitutionResidence,
    Tag.ScheduledPerformingPhysicianIdentificationSequence,
    Tag.ScheduledPerformingPhysicianName,
    Tag.ScheduledProcedureStepDescription,
    Tag.SeriesDescription,
    Tag.ServiceEpisodeDescription,
    Tag.ServiceEpisodeID,
    Tag.SourceImageSequence, // Keep in UID option but removed here
    Tag.SpecialNeeds,
    Tag.StudyComments,
    Tag.StudyDescription,
    Tag.StudyIDIssuer,
    Tag.TelephoneNumberTrial,
    Tag.TextComments,
    Tag.TextString,
    Tag.TopicAuthor,
    Tag.TopicKeywords,
    Tag.TopicSubject,
    Tag.TopicTitle,
    Tag.VerbalSourceTrial,
    Tag.VerbalSourceIdentifierCodeSequenceTrial,
    Tag.VerifyingObserverIdentificationCodeSequence, // type Z
    Tag.VerifyingObserverSequence, // type D
    Tag.VerifyingOrganization,
    Tag.VisitComments
  )

  val keepTags = hex2int(Option(sliceboxConfig.getStringList("anonymization.keep-tags").asScala).getOrElse(Seq.empty[String]))

  /**
    * From standard PS3.15 Table E.1-1
    * Remove all private attributes
    * Remove overlay data
    * Remove, set empty or modify certain attributes
    */
  def anonFlow: Flow[DicomPart, DicomPart, NotUsed] =
    Flow[DicomPart]
      .via(groupLengthDiscardFilter)
      .via(toIndeterminateLengthSequences)
      .via(toUtf8Flow)
      .via(tagFilter(_ => true)(tagPath =>
        !tagPath.toList.map(_.tag).exists(tag =>
          (isPrivate(tag) && !keepTags.contains(tag)) || isOverlay(tag) || (removeTags.contains(tag) && !keepTags.contains(tag))))) // remove private, overlay and PHI attributes but keep keepTags

      .via(modifyFlow( // modify, clear and insert
      modify(Tag.AccessionNumber, bytes => if (bytes.nonEmpty) createAccessionNumber(bytes) else bytes),
      modify(Tag.ConcatenationUID, createUid),
      clear(Tag.ContentCreatorName),
      modify(Tag.ContextGroupExtensionCreatorUID, createUid),
      clear(Tag.ContrastBolusAgent),
      modify(Tag.CreatorVersionUID, createUid),
      insert(Tag.DeidentificationMethod, _ => toAsciiBytes("Retain Longitudinal Full Dates Option", VR.LO)),
      modify(Tag.DimensionOrganizationUID, createUid),
      modify(Tag.DoseReferenceUID, createUid),
      modify(Tag.FiducialUID, createUid),
      clear(Tag.FillerOrderNumberImagingServiceRequest),
      maybe_modify(Tag.FrameOfReferenceUID, !keepTags.contains(Tag.FrameOfReferenceUID), createUid),
      modify(Tag.InstanceCreatorUID, createUid),
      modify(Tag.IrradiationEventUID, createUid),
      modify(Tag.LargePaletteColorLookupTableUID, createUid),
      modify(Tag.MediaStorageSOPInstanceUID, createUid),
      modify(Tag.ObservationSubjectUIDTrial, createUid),
      modify(Tag.ObservationUID, createUid),
      modify(Tag.PaletteColorLookupTableUID, createUid),
      insert(Tag.PatientIdentityRemoved, _ => toAsciiBytes("YES", VR.CS)),
      maybe_insert(Tag.PatientID, !keepTags.contains(Tag.PatientID),_ => createUid(null)),
      insert(Tag.PatientName, _ => createUid(null)),
      clear(Tag.PlacerOrderNumberImagingServiceRequest),
      maybe_modify(Tag.ReferencedFrameOfReferenceUID, !keepTags.contains(Tag.ReferencedFrameOfReferenceUID),createUid),
      modify(Tag.ReferencedGeneralPurposeScheduledProcedureStepTransactionUID, createUid),
      modify(Tag.ReferencedObservationUIDTrial, createUid),
      maybe_modify(Tag.ReferencedSOPInstanceUID, !keepTags.contains(Tag.ReferencedSOPInstanceUID), createUid),
      modify(Tag.ReferencedSOPInstanceUIDInFile, createUid),
      clear(Tag.ReferringPhysicianName),
      modify(Tag.RelatedFrameOfReferenceUID, createUid),
      modify(Tag.RequestedSOPInstanceUID, createUid),
      maybe_insert(Tag.SeriesInstanceUID, !keepTags.contains(Tag.SeriesInstanceUID),_ => createUid(null)),
      maybe_insert(Tag.SOPInstanceUID, !keepTags.contains(Tag.SOPInstanceUID), createUid),
      modify(Tag.StorageMediaFileSetUID, createUid),
      clear(Tag.StudyID),
      maybe_insert(Tag.StudyInstanceUID, !keepTags.contains(Tag.StudyInstanceUID), _ => createUid(null)),
      modify(Tag.SynchronizationFrameOfReferenceUID, createUid),
      modify(Tag.TargetUID, createUid),
      modify(Tag.TemplateExtensionCreatorUID, createUid),
      modify(Tag.TemplateExtensionOrganizationUID, createUid),
      modify(Tag.TransactionUID, createUid),
      modify(Tag.UID, createUid),
      clear(Tag.VerifyingObserverName)))

  /**
    * Anonymize data if not already anonymized. Assumes first `DicomPart` is a `PartialAnonymizationKeyPart` that is
    * used to determine if data has been anonymized or not.
    *
    * @return a `Flow` of `DicomParts` that will anonymize non-anonymized data but does nothing otherwise
    */
  def maybeAnonFlow: Flow[DicomPart, DicomPart, NotUsed] = conditionalFlow(
    {
      case keyPart: PartialAnonymizationKeyPart => keyPart.keyMaybe.nonEmpty
    }, anonFlow, Flow.fromFunction(identity))

}








