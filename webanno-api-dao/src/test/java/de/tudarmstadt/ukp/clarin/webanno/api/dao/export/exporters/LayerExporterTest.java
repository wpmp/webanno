/*
 * Copyright 2019
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

import static de.tudarmstadt.ukp.clarin.webanno.api.WebAnnoConst.PROJECT_TYPE_ANNOTATION;
import static de.tudarmstadt.ukp.clarin.webanno.api.WebAnnoConst.RELATION_TYPE;
import static de.tudarmstadt.ukp.clarin.webanno.api.WebAnnoConst.SPAN_TYPE;
import static de.tudarmstadt.ukp.clarin.webanno.model.AnchoringMode.SENTENCES;
import static de.tudarmstadt.ukp.clarin.webanno.model.AnchoringMode.SINGLE_TOKEN;
import static de.tudarmstadt.ukp.clarin.webanno.model.AnchoringMode.TOKENS;
import static de.tudarmstadt.ukp.clarin.webanno.model.OverlapMode.ANY_OVERLAP;
import static de.tudarmstadt.ukp.clarin.webanno.model.OverlapMode.NO_OVERLAP;
import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import java.io.File;
import java.util.List;
import java.util.zip.ZipFile;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import de.tudarmstadt.ukp.clarin.webanno.api.AnnotationSchemaService;
import de.tudarmstadt.ukp.clarin.webanno.api.export.ProjectExportRequest;
import de.tudarmstadt.ukp.clarin.webanno.api.export.ProjectImportRequest;
import de.tudarmstadt.ukp.clarin.webanno.export.model.ExportedProject;
import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationLayer;
import de.tudarmstadt.ukp.clarin.webanno.model.Project;
import de.tudarmstadt.ukp.clarin.webanno.model.ValidationMode;

public class LayerExporterTest
{
    public @Rule TemporaryFolder tempFolder = new TemporaryFolder();
    
    private @Mock AnnotationSchemaService annotationService;
    
    private Project project;
    private File workFolder;

    private LayerExporter sut;

    @Before
    public void setUp() throws Exception
    {
        initMocks(this);

        project = new Project();
        project.setId(1l);
        project.setName("Test Project");
        project.setMode(PROJECT_TYPE_ANNOTATION);
        
        workFolder = tempFolder.newFolder();
        
        when(annotationService.listAnnotationLayer(any())).thenReturn(layers());
        
        sut = new LayerExporter(annotationService);
    }

    @Test
    public void thatExportingWorks() throws Exception
    {
        // Export the project and import it again
        ArgumentCaptor<AnnotationLayer> captor = runExportImportAndFetchLayers();

        // Check that after re-importing the exported projects, they are identical to the original
        assertThat(captor.getAllValues())
                .usingElementComparatorIgnoringFields("id")
                .containsExactlyInAnyOrderElementsOf(layers());
    }
    
    private List<AnnotationLayer> layers()
    {
        AnnotationLayer layer1 = new AnnotationLayer("webanno.custom.Span", "Span",
                SPAN_TYPE, project, false, SINGLE_TOKEN, NO_OVERLAP);
        layer1.setValidationMode(ValidationMode.ALWAYS);

        AnnotationLayer layer2 = new AnnotationLayer("webanno.custom.Span2", "Span2",
                SPAN_TYPE, project, false, SENTENCES, NO_OVERLAP);
        layer2.setValidationMode(ValidationMode.NEVER);

        AnnotationLayer layer3 = new AnnotationLayer("webanno.custom.Relation", "Relation",
                RELATION_TYPE, project, true, TOKENS, ANY_OVERLAP);
        
        return asList(layer1, layer2, layer3);
    }
    
    private ArgumentCaptor<AnnotationLayer> runExportImportAndFetchLayers() throws Exception
    {
        // Export the project
        ProjectExportRequest exportRequest = new ProjectExportRequest();
        exportRequest.setProject(project);
        ExportedProject exportedProject = new ExportedProject();

        sut.exportData(exportRequest, exportedProject, workFolder);

        // Import the project again
        ArgumentCaptor<AnnotationLayer> captor = ArgumentCaptor.forClass(AnnotationLayer.class);
        doNothing().when(annotationService).createLayer(captor.capture());

        ProjectImportRequest importRequest = new ProjectImportRequest(true);
        ZipFile zipFile = mock(ZipFile.class);
        sut.importData(importRequest, project, exportedProject, zipFile);
        
        return captor;
    }
}
