/*
 * Copyright 2018
 * Ubiquitous Knowledge Processing (UKP) Lab and FG Language Technology
 * Technische Universität Darmstadt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.tudarmstadt.ukp.clarin.webanno.api.dao.export.exporters;

import static de.tudarmstadt.ukp.clarin.webanno.api.WebAnnoConst.CURATION_USER;
import static de.tudarmstadt.ukp.clarin.webanno.api.export.ProjectExportRequest.FORMAT_AUTO;
import static de.tudarmstadt.ukp.clarin.webanno.model.Mode.CURATION;
import static de.tudarmstadt.ukp.clarin.webanno.model.SourceDocumentState.CURATION_FINISHED;
import static de.tudarmstadt.ukp.clarin.webanno.model.SourceDocumentState.CURATION_IN_PROGRESS;
import static java.util.Arrays.asList;

import java.io.File;
import java.io.IOException;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import de.tudarmstadt.ukp.clarin.webanno.api.DocumentService;
import de.tudarmstadt.ukp.clarin.webanno.api.ImportExportService;
import de.tudarmstadt.ukp.clarin.webanno.api.export.ProjectExportException;
import de.tudarmstadt.ukp.clarin.webanno.api.export.ProjectExportRequest;
import de.tudarmstadt.ukp.clarin.webanno.api.export.ProjectExporter;
import de.tudarmstadt.ukp.clarin.webanno.api.export.ProjectImportRequest;
import de.tudarmstadt.ukp.clarin.webanno.api.format.FormatSupport;
import de.tudarmstadt.ukp.clarin.webanno.export.model.ExportedProject;
import de.tudarmstadt.ukp.clarin.webanno.model.Project;
import de.tudarmstadt.ukp.clarin.webanno.model.SourceDocument;
import de.tudarmstadt.ukp.clarin.webanno.support.logging.LogMessage;
import de.tudarmstadt.ukp.clarin.webanno.tsv.WebAnnoTsv3FormatSupport;

@Component
public class CuratedDocumentsExporter
    implements ProjectExporter
{
    private static final String CURATION_FOLDER = "/curation/";
    private static final String CURATION_AS_SERIALISED_CAS = "curation_ser";
    private static final String CURATION_CAS_FOLDER = "/" + CURATION_AS_SERIALISED_CAS + "/";

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final DocumentService documentService;
    private final ImportExportService importExportService;
    
    @Autowired
    public CuratedDocumentsExporter(DocumentService aDocumentService,
            ImportExportService aImportExportService)
    {
        documentService = aDocumentService;
        importExportService = aImportExportService;
    }

    @Override
    public List<Class<? extends ProjectExporter>> getImportDependencies()
    {
        return asList(SourceDocumentExporter.class);
    }
    
    
    /**
     * Copy, if exists, curation documents to a folder that will be exported as Zip file
     * 
     * @param aStage
     *            The folder where curated documents are copied to be exported as Zip File
     */
    @Override
    public void exportData(ProjectExportRequest aRequest, ExportedProject aExProject, File aStage)
        throws Exception
    {
        Project project = aRequest.getProject();
        
        // Get all the source documents from the project
        List<SourceDocument> documents = documentService.listSourceDocuments(project);

        // Determine which format to use for export
        FormatSupport format;
        if (FORMAT_AUTO.equals(aRequest.getFormat())) {
            format = new WebAnnoTsv3FormatSupport();
        }
        else {
            format = importExportService.getWritableFormatById(aRequest.getFormat())
                    .orElseGet(() -> {
                        aRequest.addMessage(LogMessage.error(this, "No writer found for format "
                                + "[%s] - exporting as WebAnno TSV instead.", 
                                aRequest.getFormat()));
                        return new WebAnnoTsv3FormatSupport();
                    });
        }
        
        int initProgress = aRequest.progress - 1;
        int i = 1;
        for (SourceDocument sourceDocument : documents) {
            File curationCasDir = new File(aStage, CURATION_CAS_FOLDER + sourceDocument.getName());
            FileUtils.forceMkdir(curationCasDir);

            File curationDir = new File(aStage, CURATION_FOLDER + sourceDocument.getName());
            FileUtils.forceMkdir(curationDir);

            // If depending on aInProgress, include only the the curation documents that are
            // finished or also the ones that are in progress
            if (
                (aRequest.isIncludeInProgress() && 
                    CURATION_IN_PROGRESS.equals(sourceDocument.getState())) ||
                CURATION_FINISHED.equals(sourceDocument.getState())
            ) {
                File curationCasFile = documentService.getCasFile(sourceDocument, CURATION_USER);
                if (curationCasFile.exists()) {
                    // Copy CAS - this is used when importing the project again
                    FileUtils.copyFileToDirectory(curationCasFile, curationCasDir);

                    // Copy secondary export format for convenience - not used during import
                    try {
                        File curationFile = importExportService.exportAnnotationDocument(
                                sourceDocument, CURATION_USER, format, CURATION_USER, CURATION);
                        FileUtils.copyFileToDirectory(curationFile, curationDir);
                        FileUtils.forceDelete(curationFile);
                    }
                    catch (Exception e) {
                        // error("Unexpected error while exporting project: " +
                        // ExceptionUtils.getRootCauseMessage(e) );
                        throw new ProjectExportException(
                                "Aborting due to unrecoverable error while exporting!");
                    }
                }
            }

            aRequest.progress = initProgress
                    + (int) Math.ceil(((double) i) / documents.size() * 10.0);
            i++;
        }
    }
    
    /**
     * Copy curation documents from the exported project
     * 
     * @param aZip
     *            the ZIP file.
     * @param aProject
     *            the project.
     * @throws IOException
     *             if an I/O error occurs.
     */
    @Override
    public void importData(ProjectImportRequest aRequest, Project aProject,
            ExportedProject aExProject, ZipFile aZip)
        throws Exception
    {
        for (Enumeration<? extends ZipEntry> zipEnumerate = aZip.entries(); zipEnumerate
                .hasMoreElements();) {
            ZipEntry entry = zipEnumerate.nextElement();
            
            log.trace("Considering ZIP entry [{}]", entry.getName());

            // Strip leading "/" that we had in ZIP files prior to 2.0.8 (bug #985)
            String entryName = ProjectExporter.normalizeEntryName(entry);
            
            if (!entryName.startsWith(CURATION_AS_SERIALISED_CAS + "/")) {
                continue;
            }
            
            String fileName = entryName.replace(CURATION_AS_SERIALISED_CAS + "/", "");
            // the user annotated the document is file name minus extension
            // (anno1.ser)
            String username = FilenameUtils.getBaseName(fileName).replace(".ser", "");

            
            // COMPATIBILITY NOTE: One might ask oneself why we extract the filename when it should
            // always be CURATION_USER. The reason is compatibility:
            // - Util WebAnno 3.4.x, the CORRECTION_USER CAS was exported to 'curation' and
            //   'curation_ser'. So for projects exported from this version, the 
            //   CuratedDocumentsExporter takes care of importing the CORRECTION_USER CASes.
            // - Since WebAnno 3.5.x, the CORRECTION_USER CAS is exported to 'annotation' and
            //   'annotation_ser'. So for projects exported from this version, the 
            //   AnnotationDocumentExporter takes care of importing the CORRECTION_USER CASes!
            
            // name of the annotation document
            fileName = fileName.replace(FilenameUtils.getName(fileName), "").replace("/", "");
            if (fileName.trim().isEmpty()) {
                continue;
            }
            SourceDocument sourceDocument = documentService.getSourceDocument(aProject,
                    fileName);
            File annotationFilePath = documentService.getCasFile(sourceDocument, username);

            FileUtils.copyInputStreamToFile(aZip.getInputStream(entry), annotationFilePath);
            
            log.info("Imported curation document content for user [" + username
                    + "] for source document [" + sourceDocument.getId() + "] in project ["
                    + aProject.getName() + "] with id [" + aProject.getId() + "]");
        }
    }
}
