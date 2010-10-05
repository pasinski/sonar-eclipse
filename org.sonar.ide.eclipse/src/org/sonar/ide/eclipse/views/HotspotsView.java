package org.sonar.ide.eclipse.views;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.viewers.*;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.progress.IWorkbenchSiteProgressService;
import org.sonar.ide.api.SonarIdeException;
import org.sonar.ide.eclipse.core.ISonarConstants;
import org.sonar.ide.eclipse.core.ISonarResource;
import org.sonar.ide.eclipse.internal.EclipseSonar;
import org.sonar.ide.eclipse.ui.AbstractSonarInfoView;
import org.sonar.ide.eclipse.ui.AbstractTableLabelProvider;
import org.sonar.ide.eclipse.utils.PlatformUtils;
import org.sonar.ide.eclipse.utils.SelectionUtils;
import org.sonar.wsclient.services.Measure;
import org.sonar.wsclient.services.Resource;
import org.sonar.wsclient.services.ResourceQuery;

import com.google.common.collect.Lists;

import java.util.List;

public class HotspotsView extends AbstractSonarInfoView {

  public static final String ID = ISonarConstants.PLUGIN_ID + ".views.HotspotsView";

  private static final int LIMIT = 20;

  private TableViewer viewer;
  private Combo combo;
  private String metricKey;
  private Label resourceLabel;
  private TableViewerColumn column2;

  @Override
  protected void internalCreatePartControl(Composite parent) {
    Composite container = new Composite(parent, SWT.NULL);
    GridLayout layout = new GridLayout(3, false);
    container.setLayout(layout);

    Label hotspotsLabel = new Label(container, SWT.NONE);
    hotspotsLabel.setText("Hotspots by");

    combo = new Combo(container, SWT.READ_ONLY | SWT.TOP);

    resourceLabel = new Label(container, SWT.NONE);
    GridData gridData = new GridData();
    gridData.horizontalAlignment = SWT.FILL;
    gridData.grabExcessHorizontalSpace = true;
    resourceLabel.setLayoutData(gridData);

    viewer = new TableViewer(container);
    gridData = new GridData();
    gridData.horizontalSpan = 3;
    gridData.horizontalAlignment = SWT.FILL;
    gridData.grabExcessHorizontalSpace = true;
    gridData.grabExcessVerticalSpace = true;
    gridData.verticalAlignment = SWT.FILL;
    viewer.getTable().setLayoutData(gridData);
    viewer.getTable().setHeaderVisible(true);
    viewer.getTable().setLinesVisible(true);

    TableViewerColumn column1 = new TableViewerColumn(viewer, SWT.LEFT);
    column1.getColumn().setText("Resource");
    column1.getColumn().setWidth(200);
    column2 = new TableViewerColumn(viewer, SWT.LEFT);
    column2.getColumn().setWidth(200);

    viewer.setContentProvider(ArrayContentProvider.getInstance());
    viewer.setLabelProvider(new HotspotsLabelProvider());
    viewer.addDoubleClickListener(new IDoubleClickListener() {
      public void doubleClick(DoubleClickEvent event) {
        Object object = SelectionUtils.getSingleElement(viewer.getSelection());
        // adapt org.sonar.wsclient.services.Resource to IFile
        IFile file = PlatformUtils.adapt(object, IFile.class);
        if (file != null) {
          PlatformUtils.openEditor(file);
        }
      }
    });

    combo.add("complexity");
    combo.add("uncovered_lines");
    combo.add("function_complexity");
    combo.add("public_undocumented_api");
    combo.add("weighted_violations");
    combo.add("duplicated_lines");
    combo.addSelectionListener(new SelectionAdapter() {
      @Override
      public void widgetSelected(SelectionEvent e) {
        metricKey = combo.getText();
        if (getInput() != null) {
          doSetInput(getInput());
        }
      }
    });
    combo.select(0);
    metricKey = combo.getText();
  }

  private class HotspotsLabelProvider extends AbstractTableLabelProvider {
    @Override
    public String getColumnText(Object element, int columnIndex) {
      HotspotMeasure measure = (HotspotMeasure) element;
      switch (columnIndex) {
        case 0:
          return measure.getName();
        case 1:
          return measure.getValue();
        default:
          throw new SonarIdeException("Should never happen");
      }
    }
  }

  @Override
  protected Control getControl() {
    return viewer.getControl();
  }

  private String getMetricKey() {
    return metricKey;
  }

  private void update(final Object content) {
    getSite().getShell().getDisplay().asyncExec(new Runnable() {
      public void run() {
        resourceLabel.setText("for project " + getInput().getProject().getName());
        column2.getColumn().setText(metricKey);
        viewer.setInput(content);
      }
    });
  }

  @Override
  protected ISonarResource findSonarResource(Object element) {
    ISonarResource sonarResource = super.findSonarResource(element);
    if (sonarResource == null) {
      return null;
    }
    if ( !(sonarResource.getResource() instanceof IProject)) {
      sonarResource = super.findSonarResource(sonarResource.getProject());
    }
    return sonarResource;
  }

  class HotspotMeasure implements IAdaptable {

    private Resource resource;
    private String value;

    public HotspotMeasure(Resource resource, Measure measure) {
      this.resource = resource;
      this.value = measure.getFormattedValue("");
    }

    public String getName() {
      return resource.getName();
    }

    public String getValue() {
      return value;
    }

    public Object getAdapter(Class adapter) {
      if (adapter == IFile.class) {
        return PlatformUtils.adapt(resource, IFile.class);
      }
      return null;
    }

  }

  /**
   * @param input ISonarResource to be shown in the view (can't be null)
   */
  @Override
  protected void doSetInput(Object input) {
    final ISonarResource sonarResource = (ISonarResource) input;
    Job job = new Job("Loading hotspots") {
      @Override
      protected IStatus run(IProgressMonitor monitor) {
        monitor.beginTask("Loading hotspots for " + sonarResource.getKey(), IProgressMonitor.UNKNOWN);
        EclipseSonar index = EclipseSonar.getInstance(sonarResource.getProject());
        List<Resource> resources = index.getSonar().findAll(getResourceQuery(sonarResource));
        List<HotspotMeasure> measures = Lists.newArrayList();
        for (Resource resource : resources) {
          for (Measure measure : resource.getMeasures()) {
            measures.add(new HotspotMeasure(resource, measure));
          }
        }
        update(measures);
        monitor.done();
        return Status.OK_STATUS;
      }
    };
    IWorkbenchSiteProgressService siteService = (IWorkbenchSiteProgressService) getSite().getAdapter(IWorkbenchSiteProgressService.class);
    siteService.schedule(job);
  }

  private ResourceQuery getResourceQuery(ISonarResource resource) {
    return ResourceQuery.createForMetrics(resource.getKey(), getMetricKey())
        .setScopes(Resource.SCOPE_ENTITY)
        .setDepth(ResourceQuery.DEPTH_UNLIMITED)
        .setLimit(LIMIT);
  }

}