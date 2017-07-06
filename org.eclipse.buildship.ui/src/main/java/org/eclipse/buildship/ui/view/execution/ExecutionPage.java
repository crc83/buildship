/*
 * Copyright (c) 2015 the original author or authors.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Simon Scholz (vogella GmbH) - initial API and implementation and initial documentation
 */

package org.eclipse.buildship.ui.view.execution;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.gradle.tooling.LongRunningOperation;
import org.gradle.tooling.events.FailureResult;
import org.gradle.tooling.events.FinishEvent;
import org.gradle.tooling.events.OperationDescriptor;
import org.gradle.tooling.events.ProgressEvent;
import org.gradle.tooling.events.StartEvent;
import org.gradle.tooling.events.task.TaskOperationDescriptor;
import org.gradle.tooling.events.test.JvmTestKind;
import org.gradle.tooling.events.test.JvmTestOperationDescriptor;

import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.common.collect.TreeTraverser;

import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider;
import org.eclipse.jface.viewers.DoubleClickEvent;
import org.eclipse.jface.viewers.IDoubleClickListener;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.TreeViewerColumn;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ControlAdapter;
import org.eclipse.swt.events.ControlEvent;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.TreeColumn;
import org.eclipse.ui.IActionBars;

import org.eclipse.buildship.core.console.ProcessDescription;
import org.eclipse.buildship.ui.external.viewer.FilteredTree;
import org.eclipse.buildship.ui.external.viewer.PatternFilter;
import org.eclipse.buildship.ui.util.nodeselection.ActionShowingContextMenuListener;
import org.eclipse.buildship.ui.util.nodeselection.NodeSelection;
import org.eclipse.buildship.ui.util.nodeselection.NodeSelectionProvider;
import org.eclipse.buildship.ui.util.nodeselection.SelectionHistoryManager;
import org.eclipse.buildship.ui.util.nodeselection.SelectionSpecificAction;
import org.eclipse.buildship.ui.view.BasePage;
import org.eclipse.buildship.ui.view.CollapseTreeNodesAction;
import org.eclipse.buildship.ui.view.ExpandTreeNodesAction;
import org.eclipse.buildship.ui.view.MultiPageView;
import org.eclipse.buildship.ui.view.PageSite;
import org.eclipse.buildship.ui.view.ShowFilterAction;

/**
 * Displays the tree of a single build execution.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class ExecutionPage extends BasePage<FilteredTree> implements NodeSelectionProvider {

    private final ProcessDescription processDescription;
    private final LongRunningOperation operation;
    private final ExecutionViewState state;
    private final Map<OperationDescriptor, OperationItem> allItems;
    private final Map<OperationItem, Boolean> activeItems;

    private FilteredTree filteredTree;
    private SelectionHistoryManager selectionHistoryManager;
    private TreeViewerColumn nameColumn;
    private TreeViewerColumn durationColumn;
    private ExecutionProgressListener progressListener;

    private OpenBuildScanAction openBuildScanAction;

    public ExecutionPage(ProcessDescription processDescription, LongRunningOperation operation, ExecutionViewState state) {
        this.processDescription = processDescription;
        this.operation = operation;
        this.state = state;
        this.allItems = Maps.newHashMap();
        // if the value is true then the item should be removed from the active items set after the next refresh
        this.activeItems = Maps.newHashMap();
    }

    public ProcessDescription getProcessDescription() {
        return this.processDescription;
    }

    @Override
    public String getDisplayName() {
        return this.processDescription.getName();
    }

    @Override
    public FilteredTree createPageWithResult(Composite parent) {
        // configure tree
        this.filteredTree = new FilteredTree(parent, SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL, new PatternFilter(true));
        this.filteredTree.setShowFilterControls(false);
        this.filteredTree.getViewer().getTree().setHeaderVisible(true);
        this.filteredTree.getViewer().setContentProvider(new ExecutionPageContentProvider());
        this.filteredTree.getViewer().setUseHashlookup(true);

        this.nameColumn = new TreeViewerColumn(this.filteredTree.getViewer(), SWT.NONE);
        this.nameColumn.getColumn().setText(ExecutionViewMessages.Tree_Column_Operation_Name_Text);
        this.nameColumn.getColumn().setWidth(this.state.getHeaderNameColumnWidth());
        this.nameColumn.setLabelProvider(new DelegatingStyledCellLabelProvider(new ExecutionPageNameLabelProvider()));

        this.durationColumn = new TreeViewerColumn(this.filteredTree.getViewer(), SWT.RIGHT);
        this.durationColumn.getColumn().setText(ExecutionViewMessages.Tree_Column_Operation_Duration_Text);
        this.durationColumn.getColumn().setWidth(this.state.getHeaderDurationColumnWidth());
        this.durationColumn.setLabelProvider(new DelegatingStyledCellLabelProvider(new ExecutionPageDurationLabelProvider()));

        // keep header size synchronized between pages
        this.nameColumn.getColumn().addControlListener(new ControlAdapter() {

            @Override
            public void controlResized(ControlEvent e) {
                int newWidth = ExecutionPage.this.nameColumn.getColumn().getWidth();
                ExecutionPage.this.state.setHeaderNameColumnWidth(newWidth);
                ExecutionsView view = (ExecutionsView) getSite().getViewSite().getPart();
                for (ExecutionPage page : FluentIterable.from(view.getPages()).filter(ExecutionPage.class)) {
                    if (page != ExecutionPage.this) {
                        TreeColumn column = page.nameColumn.getColumn();
                        int columnWidth = column.getWidth();
                        if (columnWidth != newWidth) {
                            column.setWidth(newWidth);
                        }
                    }
                }
            }
        });

        this.durationColumn.getColumn().addControlListener(new ControlAdapter() {

            @Override
            public void controlResized(ControlEvent e) {
                int newWidth = ExecutionPage.this.durationColumn.getColumn().getWidth();
                ExecutionPage.this.state.setHeaderDurationColumnWidth(newWidth);
                ExecutionsView view = (ExecutionsView) getSite().getViewSite().getPart();
                for (ExecutionPage page : FluentIterable.from(view.getPages()).filter(ExecutionPage.class)) {
                    if (page != ExecutionPage.this) {
                        TreeColumn column = page.durationColumn.getColumn();
                        int columnWidth = column.getWidth();
                        if (columnWidth != newWidth) {
                            column.setWidth(newWidth);
                        }
                    }
                }
            }
        });

        // manage the selection history
        this.selectionHistoryManager = new SelectionHistoryManager(this.filteredTree.getViewer());

        // set tree root node
        OperationItem root = new OperationItem();
        this.filteredTree.getViewer().setInput(root);
        this.allItems.put(null, root);

        this.progressListener = new ExecutionProgressListener(this, this.processDescription.getJob());
        this.operation.addProgressListener(this.progressListener);

        // return the tree as the outermost page control
        return this.filteredTree;
    }

    public void onProgress(ProgressEvent progressEvent) {
        OperationDescriptor descriptor = progressEvent.getDescriptor();
        if (isExcluded(descriptor)) {
            return;
        }
        OperationItem operationItem = this.allItems.get(descriptor);
        if (null == operationItem) {
            operationItem = new OperationItem((StartEvent) progressEvent);
            this.allItems.put(descriptor, operationItem);
            this.activeItems.put(operationItem, Boolean.FALSE);
        } else {
            operationItem.setFinishEvent((FinishEvent) progressEvent);
            this.activeItems.put(operationItem, Boolean.TRUE);
            if (isJvmTestSuite(descriptor) && operationItem.getChildren().isEmpty()) {
                // do not display test suite nodes that have no children (unwanted artifacts from Gradle)
                OperationItem parentOperationItem = this.allItems.get(findFirstNonExcludedParent(descriptor));
                parentOperationItem.removeChild(operationItem);
                return;
            }
        }

        // attach to (first non-excluded) parent, if this is a new operation (in case of StartEvent)
        OperationItem parentExecutionItem = this.allItems.get(findFirstNonExcludedParent(descriptor));
        parentExecutionItem.addChild(operationItem);
    }

    private boolean isExcluded(OperationDescriptor descriptor) {
        // ignore the 'artificial' events issued for the root test event and for each forked test
        // process event
        if (descriptor instanceof JvmTestOperationDescriptor) {
            JvmTestOperationDescriptor jvmTestOperationDescriptor = (JvmTestOperationDescriptor) descriptor;
            return jvmTestOperationDescriptor.getSuiteName() != null && jvmTestOperationDescriptor.getClassName() == null;
        } else {
            return false;
        }
    }

    private OperationDescriptor findFirstNonExcludedParent(OperationDescriptor descriptor) {
        while (isExcluded(descriptor.getParent())) {
            descriptor = descriptor.getParent();
        }
        return descriptor.getParent();
    }

    public void refreshChangedItems() {
        TreeViewer viewer = this.filteredTree.getViewer();
        for (OperationItem item : this.activeItems.keySet()) {
            viewer.update(item, null);
            if (shouldBeVisible(item)) {
                viewer.expandToLevel(item, 0);
            }
        }
        viewer.refresh(false);

        Iterator<OperationItem> items = this.activeItems.keySet().iterator();
        while (items.hasNext()) {
            OperationItem item = items.next();
            if (this.activeItems.get(item)) {
                items.remove();
            }
        }
    }

    private boolean shouldBeVisible(OperationItem item) {
        return isOnMax2ndLevel(item) || isTaskOperation(item) || isFailedOperation(item);
    }

    private boolean isOnMax2ndLevel(OperationItem item) {
        int level = 2;
        while (level >= 0) {
            if (item.getParent() == null) {
                return true;
            } else {
                level--;
                item = item.getParent();
            }
        }
        return false;
    }

    private boolean isTaskOperation(OperationItem item) {
        return item.getStartEvent().getDescriptor() instanceof TaskOperationDescriptor;
    }

    private boolean isFailedOperation(OperationItem item) {
            FinishEvent finishEvent = item.getFinishEvent();
            return finishEvent != null ? finishEvent.getResult() instanceof FailureResult : false;
    }
    private boolean isJvmTestSuite(OperationDescriptor descriptor) {
        if (descriptor instanceof JvmTestOperationDescriptor) {
            JvmTestOperationDescriptor testOperationDescriptor = (JvmTestOperationDescriptor) descriptor;
            if (testOperationDescriptor.getJvmTestKind() == JvmTestKind.SUITE ) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void init(PageSite pageSite) {
        super.init(pageSite);

        populateToolBar();
        registerContextMenu(pageSite);
        registerListeners();
    }

    private void populateToolBar() {
        IActionBars actionBars = getSite().getActionBars();
        IToolBarManager toolbarManager = actionBars.getToolBarManager();
        toolbarManager.appendToGroup(MultiPageView.PAGE_GROUP, new ExpandTreeNodesAction(getPageControl().getViewer()));
        toolbarManager.appendToGroup(MultiPageView.PAGE_GROUP, new CollapseTreeNodesAction(getPageControl().getViewer()));
        toolbarManager.appendToGroup(MultiPageView.PAGE_GROUP, new ShowFilterAction(getPageControl()));
        toolbarManager.appendToGroup(MultiPageView.PAGE_GROUP, new Separator());
        toolbarManager.appendToGroup(MultiPageView.PAGE_GROUP, new SwitchToConsoleViewAction(this));
        toolbarManager.appendToGroup(MultiPageView.PAGE_GROUP, new Separator());
        toolbarManager.appendToGroup(MultiPageView.PAGE_GROUP, new RerunFailedTestsAction(this));
        toolbarManager.appendToGroup(MultiPageView.PAGE_GROUP, this.openBuildScanAction = new OpenBuildScanAction(this.getProcessDescription()));
        toolbarManager.appendToGroup(MultiPageView.PAGE_GROUP, new Separator());
        toolbarManager.appendToGroup(MultiPageView.PAGE_GROUP, new CancelBuildExecutionAction(this));
        toolbarManager.appendToGroup(MultiPageView.PAGE_GROUP, new RerunBuildExecutionAction(this));
        toolbarManager.appendToGroup(MultiPageView.PAGE_GROUP, new RemoveTerminatedExecutionPageAction(this));
        toolbarManager.appendToGroup(MultiPageView.PAGE_GROUP, new RemoveAllTerminatedExecutionPagesAction(this));
        toolbarManager.update(true);
    }

    private void registerContextMenu(PageSite pageSite) {
        TreeViewer treeViewer = getPageControl().getViewer();
        MenuManager menuManager = new MenuManager();
        menuManager.setRemoveAllWhenShown(true);
        menuManager.addMenuListener(createContextMenuListener(treeViewer));
        Menu contextMenu = menuManager.createContextMenu(treeViewer.getTree());
        treeViewer.getTree().setMenu(contextMenu);
        pageSite.getViewSite().registerContextMenu(menuManager, treeViewer);
    }

    private ActionShowingContextMenuListener createContextMenuListener(TreeViewer treeViewer) {
        RunTestAction runTestAction = new RunTestAction(this);
        ShowFailureAction showFailureAction = new ShowFailureAction(this);
        OpenTestSourceFileAction openTestSourceFileAction = new OpenTestSourceFileAction(this);
        ExpandTreeNodesAction expandNodesAction = new ExpandTreeNodesAction(treeViewer);
        CollapseTreeNodesAction collapseNodesAction = new CollapseTreeNodesAction(treeViewer);

        List<SelectionSpecificAction> contextMenuActions = ImmutableList.<SelectionSpecificAction>of(runTestAction, showFailureAction, openTestSourceFileAction, expandNodesAction, collapseNodesAction);
        List<SelectionSpecificAction> contextMenuActionsPrecededBySeparator = ImmutableList.<SelectionSpecificAction>of(openTestSourceFileAction, expandNodesAction);
        ImmutableList<SelectionSpecificAction> contextMenuActionsSucceededBySeparator = ImmutableList.of();

        return new ActionShowingContextMenuListener(this, contextMenuActions, contextMenuActionsPrecededBySeparator, contextMenuActionsSucceededBySeparator);
    }

    private void registerListeners() {
        // navigate to source file on double click or when pressing enter
        getPageControl().getViewer().addDoubleClickListener(new IDoubleClickListener() {

            @Override
            public void doubleClick(DoubleClickEvent event) {
                OpenTestSourceFileAction openTestSourceFileAction = new OpenTestSourceFileAction(ExecutionPage.this);
                openTestSourceFileAction.run();
            }
        });
    }

    public FluentIterable<OperationItem> filterTreeNodes(Predicate<OperationItem> predicate) {
        OperationItem root = (OperationItem) getPageControl().getViewer().getInput();
        if (root == null) {
            return FluentIterable.from(ImmutableList.<OperationItem>of());
        }

        return new TreeTraverser<OperationItem>() {

            @Override
            public Iterable<OperationItem> children(OperationItem operationItem) {
                return operationItem.getChildren();
            }
        }.breadthFirstTraversal(root).filter(predicate);
    }

    @Override
    public Object getAdapter(Class adapter) {
        if (FilteredTree.class.equals(adapter)) {
            return getPageControl();
        } else if (adapter.isAssignableFrom(TreeViewer.class)) {
            // isAssignableFrom also applies for the ISelectionProvider interface
            return getPageControl().getViewer();
        }
        return Platform.getAdapterManager().getAdapter(this, adapter);
    }

    @Override
    public boolean isCloseable() {
        return this.processDescription.getJob().getState() == Job.NONE;
    }

    @Override
    public NodeSelection getSelection() {
        return this.selectionHistoryManager.getSelectionHistory();
    }

    @Override
    public void dispose() {
        if (this.openBuildScanAction != null) {
            this.openBuildScanAction.dispose();
        }
        if (this.selectionHistoryManager != null) {
            this.selectionHistoryManager.dispose();
        }
        super.dispose();
    }

}
