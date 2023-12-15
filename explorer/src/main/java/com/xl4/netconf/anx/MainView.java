/**
 * Copyright (c) 2018 Cisco Systems
 *
 * Author: Steven Barth <stbarth@cisco.com>
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

package com.xl4.netconf.anx;

import com.xl4.netconf.anc.*;
import com.vaadin.annotations.PreserveOnRefresh;
import com.vaadin.data.TreeData;
import com.vaadin.data.provider.TreeDataProvider;
import com.vaadin.event.ShortcutAction.KeyCode;
import com.vaadin.icons.VaadinIcons;
import com.vaadin.navigator.View;
import com.vaadin.navigator.ViewBeforeLeaveEvent;
import com.vaadin.navigator.ViewChangeListener.ViewChangeEvent;
import com.vaadin.server.FileDownloader;
import com.vaadin.server.FileResource;
import com.vaadin.server.Page;
import com.vaadin.server.StreamResource;
import com.vaadin.server.VaadinService;
import com.vaadin.shared.ui.ContentMode;
import com.vaadin.shared.ui.grid.HeightMode;
import com.vaadin.ui.*;
import com.vaadin.ui.MenuBar.MenuItem;
import com.vaadin.ui.themes.ValoTheme;
import org.opendaylight.yangtools.yang.model.api.Module;
import org.opendaylight.yangtools.yang.model.repo.api.YangTextSchemaSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Main view showing schema and data tree
 */
@SuppressWarnings("serial")
@PreserveOnRefresh
public final class MainView extends VerticalLayout implements View {
    private VerticalLayout sidebarPanel;
    private Tree<WrappedYangNode> schemaTree;
    private Tree<XMLElement> dataTree;
    private XMLElement dataElements = new XMLElement(null, "data");
    private String dataQuery;
    private Netconf.Datastore dataSource;
    private Panel treePanel = new Panel();
    private TextArea descriptionLabel;

    private GNMITools gnmiTools;
    private static final boolean SUPPORT_GNMI = false;

    private String command = "get";

    String host;
    String username;
    String password;
    NetconfClient client;
    NetconfYangParser parser;
    WrappedYangNode selectedNode;
    XMLElement selectedData;

    private MessageCallback callback;

    public MainView(String host, String username, String password,
            NetconfClient client, NetconfYangParser parser, Map<String,String> capabilities) {
        this.host = host;
        this.username = username;
        this.password = password;
	      this.client = client;
	      this.parser = parser;
        
        setSizeFull();
        setMargin(false);

        // Build topbar
        String basepath = VaadinService.getCurrent().getBaseDirectory().getAbsolutePath();
        FileResource resource = new FileResource(new File(basepath + "/WEB-INF/images/excelfore.png"));
        Image image = new Image(null, resource);
        image.addStyleName("xl4-logo");

        Label welcome = new Label("Excelfore Yang Explorer");
        welcome.addStyleName("topbartitle");
        welcome.setSizeUndefined();
        
        HorizontalLayout labels = new HorizontalLayout(image, welcome);
        labels.setSpacing(false);

        Label padding = new Label();

        Label connected = new Label(String.format("Device %s (%d YANG models)",
                host, parser.getSchemaContext().getModules().size()));

        Button refreshButton = new Button(VaadinIcons.REFRESH);
        refreshButton.setPrimaryStyleName(ValoTheme.BUTTON_BORDERLESS);

        Button switchMode = new Button(VaadinIcons.EXCHANGE);
        switchMode.setDescription("Switch to Sales Mode");
        switchMode.setPrimaryStyleName(ValoTheme.BUTTON_BORDERLESS);
        switchMode.addClickListener(x -> {
            if (callback != null) {
              callback.onMessageReceived("sales");
            }
        });

        Button disconnectButton = new Button(VaadinIcons.SIGN_OUT);
        disconnectButton.setPrimaryStyleName(ValoTheme.BUTTON_BORDERLESS);
        disconnectButton.addClickListener(x -> {
            try {
                client.close();
            } catch (Exception e) {}
            Page.getCurrent().reload();
        });

        HorizontalLayout topbar = new HorizontalLayout();
        topbar.setDefaultComponentAlignment(Alignment.MIDDLE_LEFT);
        topbar.addComponents(labels, padding, connected, refreshButton, switchMode, disconnectButton);
        topbar.setExpandRatio(padding, 1.0f);
        topbar.setWidth("100%");
        topbar.setMargin(true);
        topbar.addStyleName("topbar");
        addComponent(topbar);

        // Define main layout: sidebar + content
        HorizontalLayout mainLayout = new HorizontalLayout();
        mainLayout.setSizeFull();
        mainLayout.setMargin(false);
        addComponent(mainLayout);
        setExpandRatio(mainLayout, 1.0f);

        // Define static part of sidebar, this will always be visible
        VerticalLayout sidebar = new VerticalLayout();
        sidebar.addStyleName(ValoTheme.MENU_PART);
        sidebar.addStyleName("no-vertical-drag-hints");
        sidebar.addStyleName("no-horizontal-drag-hints");
        Panel sideBarPanel = new Panel(sidebar);
        mainLayout.addComponent(sideBarPanel);

        HorizontalLayout buttonLayout = new HorizontalLayout();
        Button homeButton = new Button("Start", VaadinIcons.HOME);
        homeButton.addStyleName(ValoTheme.BUTTON_PRIMARY);
        homeButton.addClickListener(x -> showHomeScreen());
        buttonLayout.addComponent(homeButton);

        try {
            buttonLayout.addComponent(new NetconfTools(this).createComponent());
        } catch (NetconfException e) {
            Notification.show(e.getMessage(), Notification.Type.ERROR_MESSAGE);
        }
        sidebar.addComponent(buttonLayout);

        HorizontalLayout downloadTools = new HorizontalLayout();
        downloadTools.setDefaultComponentAlignment(Alignment.BOTTOM_CENTER);

        ComboBox<YangTextSchemaSource> modelSelect = new ComboBox<>("YANG Models");
        modelSelect.setWidth("400px");
        modelSelect.setIcon(VaadinIcons.DOWNLOAD);
        modelSelect.setItemCaptionGenerator(x -> x.getIdentifier().toYangFilename());
        modelSelect.setItems(parser.getSources().stream().sorted((a, b) ->
                a.getIdentifier().toYangFilename().compareTo(b.getIdentifier().toYangFilename())));
        modelSelect.setEmptySelectionAllowed(false);
        modelSelect.setTextInputAllowed(true);

        Button viewButton = new Button("View", VaadinIcons.FILE_CODE);
        viewButton.addStyleName(ValoTheme.BUTTON_FRIENDLY);
        viewButton.setEnabled(false);
        viewButton.addClickListener(x -> {
            Window yangWindow = new Window("YANG Model "
                .concat(modelSelect.getValue().getIdentifier().toYangFilename()));
            yangWindow.setModal(true);
            yangWindow.setDraggable(true);
            yangWindow.setResizable(false);
            yangWindow.setWidth("1000px");
            yangWindow.setHeight("700px");

            try {
                ByteArrayOutputStream yangStream = new ByteArrayOutputStream();
                modelSelect.getValue().copyTo(yangStream);

                TextArea yangText = new TextArea();
                yangText.setReadOnly(true);
                yangText.setSizeFull();
                yangText.setValue(new String(yangStream.toByteArray(), StandardCharsets.UTF_8));
                
                VerticalLayout yangLayout = new VerticalLayout(yangText);
                yangLayout.setSizeFull();

                yangWindow.setContent(yangLayout);
                UI.getCurrent().addWindow(yangWindow);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        modelSelect.addValueChangeListener(x -> viewButton.setEnabled(x.getValue() != null));

        Button downloadButton = new Button("Download all", VaadinIcons.FILE_ZIP);
        downloadButton.addStyleName(ValoTheme.BUTTON_FRIENDLY);
        new FileDownloader(new StreamResource(() -> {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            ZipOutputStream zipStream = new ZipOutputStream(outputStream);
            zipStream.setLevel(9);

            parser.getSources().forEach(source -> {
                try {
                    zipStream.putNextEntry(new ZipEntry(source.getIdentifier().toYangFilename()));
                    source.copyTo(zipStream);
                    zipStream.closeEntry();
                } catch (IOException e) {}
            });

            try {
                zipStream.close();
            } catch (IOException e) {};

            return new ByteArrayInputStream(outputStream.toByteArray());
        }, "yang-models-" + host + ".zip")).extend(downloadButton);
        downloadTools.addComponents(modelSelect, viewButton, downloadButton);
        sidebar.addComponent(downloadTools);

        ComboBox<String> capabilitySelect = new ComboBox<>("NETCONF Capabilities", capabilities.entrySet().stream()
                .map(x -> x.getKey().concat(x.getValue())).sorted().collect(Collectors.toList()));
        capabilitySelect.setWidth("700px");
        capabilitySelect.setIcon(VaadinIcons.LINES);
        sidebar.addComponent(capabilitySelect);

        if (capabilities.containsKey("http://cisco.com/ns/yang/Cisco-IOS-XR-telemetry-model-driven-cfg")) {
            sidebar.addComponent(new TelemetryTools(this).createComponent());
        }

        if (SUPPORT_GNMI) {
            sidebar.addComponent((gnmiTools = new GNMITools(this)).createComponent());
        }

        sidebarPanel = new VerticalLayout();
        sidebarPanel.setMargin(false);
        sidebar.addComponent(sidebarPanel);
        sidebar.setExpandRatio(sidebarPanel, 1.0f);

        showHomeScreen();

        // Define content (right-hand side)
        VerticalLayout contentLayout = new VerticalLayout();
        contentLayout.setSizeFull();

        Panel contentPanel = new Panel(contentLayout);
        contentPanel.setSizeFull();
        mainLayout.addComponent(contentPanel);

        mainLayout.setExpandRatio(sideBarPanel, 38);
        mainLayout.setExpandRatio(contentPanel, 62);

        // Button and filter layout
        HorizontalLayout schemaFilterLayout = new HorizontalLayout();
        schemaFilterLayout.setDefaultComponentAlignment(Alignment.MIDDLE_CENTER);

        TextField schemaModuleFilter = new TextField();
        schemaModuleFilter.setPlaceholder("Search models");
        schemaModuleFilter.setWidth("150px");
        schemaModuleFilter.focus();
        schemaFilterLayout.addComponent(schemaModuleFilter);

        TextField schemaNodeFilter = new TextField();
        schemaNodeFilter.setPlaceholder("Search nodes");
        schemaNodeFilter.setWidth("150px");
        schemaFilterLayout.addComponent(schemaNodeFilter);

        Button schemaFilterApply = new Button("Apply", VaadinIcons.SEARCH);
        schemaFilterApply.addStyleName(ValoTheme.BUTTON_FRIENDLY);
        schemaFilterApply.setClickShortcut(KeyCode.ENTER);
        schemaFilterLayout.addComponent(schemaFilterApply);

        Button schemaFilterClear = new Button("Clear", VaadinIcons.ERASER);
        schemaFilterClear.addStyleName(ValoTheme.BUTTON_FRIENDLY);
        schemaFilterLayout.addComponent(schemaFilterClear);

        HorizontalLayout dataFilterLayout = new HorizontalLayout();
        dataFilterLayout.setDefaultComponentAlignment(Alignment.BOTTOM_CENTER);
        dataFilterLayout.setVisible(false);

        TextField dataNodeFilter = new TextField();
        dataNodeFilter.setPlaceholder("Search nodes");
        dataNodeFilter.setWidth("150px");
        dataNodeFilter.focus();
        dataFilterLayout.addComponent(dataNodeFilter);

        TextField dataValueFilter = new TextField();
        dataValueFilter.setPlaceholder("Search values");
        dataValueFilter.setWidth("150px");
        dataFilterLayout.addComponent(dataValueFilter);

        Button dataFilterApply = new Button("Apply", VaadinIcons.SEARCH);
        dataFilterApply.addStyleName(ValoTheme.BUTTON_FRIENDLY);
        dataFilterApply.setClickShortcut(KeyCode.ENTER);
        dataFilterLayout.addComponent(dataFilterApply);
        dataFilterLayout.setComponentAlignment(dataFilterApply, Alignment.BOTTOM_CENTER);

        Button dataFilterClear = new Button("Clear", VaadinIcons.ERASER);
        dataFilterClear.addStyleName(ValoTheme.BUTTON_FRIENDLY);
        dataFilterLayout.addComponent(dataFilterClear);
        dataFilterLayout.setComponentAlignment(dataFilterClear, Alignment.BOTTOM_CENTER);

        // Label layout
        HorizontalLayout labelLayout = new HorizontalLayout();
        labelLayout.setWidth("100%");

        Label modeLabel = new Label();
        modeLabel.setContentMode(ContentMode.HTML);
        modeLabel.setValue(VaadinIcons.FILE_TREE.getHtml() + " Schema View");
        modeLabel.addStyleName(ValoTheme.LABEL_H3);
        modeLabel.addStyleName(ValoTheme.LABEL_BOLD);

        Consumer<Netconf.Datastore> showSourceAction = x -> {
            if (dataSource != x)
                dataQuery = null;
            dataSource = x;
            schemaFilterLayout.setVisible(false);
            dataFilterLayout.setVisible(true);
            dataFilterClear.click();
            modeLabel.setValue(VaadinIcons.DATABASE.getHtml() + " Data View");
        };

        // MenuBar showMenu = new MenuBar();
        // schemaFilterLayout.addComponent(showMenu);
        // contentLayout.addComponent(schemaFilterLayout);

        // MenuItem showDataMenu = showMenu.addItem("Show Data", VaadinIcons.DATABASE, null);
        // showDataMenu.addItem("Operational", x -> showSourceAction.accept(null));
        // showDataMenu.addItem("Running", x -> showSourceAction.accept(Netconf.Datastore.RUNNING));
        // showDataMenu.addItem("Candidate", x -> showSourceAction.accept(Netconf.Datastore.CANDIDATE));
        // showDataMenu.addItem("Startup", x -> showSourceAction.accept(Netconf.Datastore.STARTUP));

        VerticalLayout switchToDataViewLayout = new VerticalLayout();
        switchToDataViewLayout.setMargin(false);
        switchToDataViewLayout.setSpacing(false);

        Button showRunningData = new Button("Switch to Data View", VaadinIcons.EXCHANGE);
        showRunningData.addStyleName(ValoTheme.BUTTON_BORDERLESS_COLORED);
        showRunningData.addStyleName(ValoTheme.BUTTON_TINY);

        CheckBox dataCommandCheckBox = new CheckBox("get 'rw' data only");
        dataCommandCheckBox.addStyleName(ValoTheme.CHECKBOX_SMALL);
        dataCommandCheckBox.addStyleName("data-switch-checkbox");
        dataCommandCheckBox.addValueChangeListener(event -> {
          command = event.getValue().booleanValue() ? "get-config" : "get";
        });

        switchToDataViewLayout.addComponent(showRunningData);    
        switchToDataViewLayout.addComponent(dataCommandCheckBox);
        switchToDataViewLayout.setComponentAlignment(showRunningData, Alignment.MIDDLE_RIGHT);
        switchToDataViewLayout.setComponentAlignment(dataCommandCheckBox, Alignment.MIDDLE_RIGHT);

        Button showSchemas = new Button("Switch to Schema View", VaadinIcons.EXCHANGE);
        showSchemas.addStyleName(ValoTheme.BUTTON_BORDERLESS_COLORED);
        showSchemas.addStyleName(ValoTheme.BUTTON_TINY);
        showSchemas.setVisible(false);
        labelLayout.addComponent(modeLabel);
        labelLayout.addComponent(switchToDataViewLayout);
        labelLayout.addComponent(showSchemas);
        labelLayout.setComponentAlignment(modeLabel, Alignment.MIDDLE_LEFT);
        labelLayout.setComponentAlignment(switchToDataViewLayout, Alignment.MIDDLE_RIGHT);
        labelLayout.setComponentAlignment(showSchemas, Alignment.MIDDLE_RIGHT);

        showRunningData.addClickListener(x -> {
          showSourceAction.accept(Netconf.Datastore.RUNNING);
          switchToDataViewLayout.setVisible(false);
          showSchemas.setVisible(true);
        });

        contentLayout.addComponent(labelLayout);
        contentLayout.addComponent(schemaFilterLayout);
        contentLayout.addComponent(dataFilterLayout);

        descriptionLabel = new TextArea("Description");
        descriptionLabel.setReadOnly(true);
        descriptionLabel.setWidth("100%");
        descriptionLabel.setRows(10);
        descriptionLabel.addStyleName("description-label");
        contentLayout.addComponent(descriptionLabel);

        // Data or schema tree definition
        treePanel.setHeight("100%");
        contentLayout.addComponent(treePanel);
        contentLayout.setExpandRatio(treePanel, 1.0f);

        schemaFilterApply.addClickListener(e -> treePanel.setContent(
                showSchemaTree(schemaModuleFilter.getValue(), schemaNodeFilter.getValue())));

        dataFilterApply.addClickListener(e -> treePanel.setContent(
                showDataTree(dataNodeFilter.getValue(), dataValueFilter.getValue())));

        schemaFilterClear.addClickListener(e -> {
            schemaModuleFilter.clear();
            schemaModuleFilter.focus();
            schemaNodeFilter.clear();
            schemaFilterApply.click();
            descriptionLabel.clear();
        });

        dataFilterClear.addClickListener(e -> {
           dataNodeFilter.clear();
           dataNodeFilter.focus();
           dataValueFilter.clear();
           dataFilterApply.click();
           descriptionLabel.clear();
        });

        showSchemas.addClickListener(x -> {
            schemaFilterLayout.setVisible(true);
            dataFilterLayout.setVisible(false);
            treePanel.setContent(schemaTree);
            selectedData = null;
            modeLabel.setValue(VaadinIcons.FILE_TREE.getHtml() + " Schema View");
            switchToDataViewLayout.setVisible(true);
            showSchemas.setVisible(false);
        });

        treePanel.setContent(showSchemaTree("", ""));

        CheckBox multiCheckBox = new CheckBox("Multiple Selection");
        schemaFilterLayout.addComponent(multiCheckBox);
        multiCheckBox.addValueChangeListener(event -> {
          schemaTree.setSelectionMode(event.getValue() ? Grid.SelectionMode.MULTI : Grid.SelectionMode.SINGLE);
        });

        refreshButton.addClickListener(x -> {
            refreschSchemas();
            schemaFilterClear.click();
            dataFilterClear.click();
            sidebarPanel.removeAllComponents();
            treePanel.setContent(showSchemaTree("", ""));
            schemaTree.setSelectionMode(multiCheckBox.getValue().booleanValue() ? Grid.SelectionMode.MULTI : Grid.SelectionMode.SINGLE);
        });
    }

    // Show the schema tree based on the current collected YANG models
    private Tree<WrappedYangNode> showSchemaTree(String moduleFilter, String fieldFilter) {
        List<String> moduleQuery = Arrays.asList(moduleFilter.toLowerCase().split(" "));
        List<String> fieldQuery = Arrays.asList(fieldFilter.toLowerCase().split(" "));

        schemaTree = new Tree<>();
        schemaTree.setSelectionMode(Grid.SelectionMode.SINGLE);
        schemaTree.setItemCaptionGenerator(WrappedYangNode::getCaption);
        schemaTree.setItemIconGenerator(x -> x.isKey() ? VaadinIcons.KEY : null);
        schemaTree.addItemClickListener(x -> showYangNode(x.getItem()));

        // Iterate YANG models, apply filters and add matching schema nodes
        TreeData<WrappedYangNode> data = new TreeData<>();
        for (Module module: parser.getSchemaContext().getModules()) {
            String name = module.getName().toLowerCase();
            String description = module.getDescription().orElse("").toLowerCase();
            if (moduleQuery.stream().filter(name::contains).count() == moduleQuery.size() ||
                    moduleQuery.stream().filter(description::contains).count() == moduleQuery.size())
                new WrappedYangNode(module).addToTree(data, fieldQuery);
        }

        // Define data provide and ordering of YANG nodes and render on tree widget
        TreeDataProvider<WrappedYangNode> dataProvider = new TreeDataProvider<>(data);
        dataProvider.setSortComparator(Comparator.comparing(WrappedYangNode::isKey)
                .thenComparing(WrappedYangNode::getName)::compare);
        schemaTree.setDataProvider(dataProvider);

        // Expand the first 100 direct filter matches automatically
        int remain = 100;
        for (WrappedYangNode module: data.getRootItems())
            remain = module.applyExpand(schemaTree, remain);

        if (remain <= 0)
            Notification.show("Too many search results! They are all shown, but only 100 have been auto-expanded.",
                    Notification.Type.TRAY_NOTIFICATION);

        return schemaTree;
    }

    // Show a tree of live data from the device
    private Tree<XMLElement> showDataTree(String moduleFilter, String fieldFilter) {
        List<String> moduleQuery = Arrays.asList(moduleFilter.toLowerCase().split(" "));
        List<String> fieldQuery = Arrays.asList(fieldFilter.toLowerCase().split(" "));

        dataTree = new Tree<>();
        // Show name of the node/leaf and value (if available)
        dataTree.setItemCaptionGenerator(x -> x.getName().concat(x.stream().count() > 0 ? "" : (" = " + x.getText())));
        dataTree.addItemClickListener(x -> {
            // Build (X)Path of selected element and find namespace

            String path = "";
            String namespace = "";
            for (XMLElement element = x.getItem(); element != null; element = element.getParent()) {
                path = "/" + element.getName() + path;
                namespace = element.getNamespace();

                if (element.getAttribute("root").equals("1"))
                    break;
            }
            path = path.substring(1);
            selectedData = x.getItem();

            // Iterate YANG schemas to find the schema node associated with the data
            for (Module module: parser.getSchemaContext().getModules())
                if (module.getNamespace().toString().equals(namespace))
                    WrappedYangNode.byPath(new WrappedYangNode(module), path).ifPresent(this::showYangNode);
        });
        
        // Get selected schema elements and build a NETCONF combined subtree-filter to retrieve all of them with a single get-call
        LinkedList<XMLElement> subtreeFilter = new LinkedList<>();
        Set<WrappedYangNode> items = schemaTree.getSelectedItems();
        for (WrappedYangNode item: items) {
            boolean unique = true;

            for (WrappedYangNode c = item.getParent(); unique && c != null; c = c.getParent())
                if (items.contains(c))
                    unique = false;

            // Only add new subtree filter if we don't have it or any parent element selected already
            if (unique) {
                item.createNetconfTemplate().map(Stream::of).orElse(item.getChildren()
                    .map(WrappedYangNode::createNetconfTemplate).filter(Optional::isPresent).map(Optional::get))
                    .forEach(subtreeFilter::add);
            }
        }

        // Cache retrieved config data if selected fields are the same and just filters change
        String newQuery = subtreeFilter.stream().map(XMLElement::toXML).collect(Collectors.joining());
        if (!newQuery.equals(dataQuery)) {
            try (NetconfSession session = client.createSession()) {
                // Query peer using NETCONF to retrieve current data using get or get-config
                if (dataSource == null) {
                    try {
                        dataElements = subtreeFilter.isEmpty() ? session.get() : session.get(subtreeFilter);
                    } catch (NetconfException.RPCException e) {
                        e.printStackTrace();
                        Notification.show("The device cowardly refused to send operational data, thus " +
                                "displaying configuration only. You may use 'Show Schemas' to go back, " +
                                "select individual supported schemas and try 'Show Data' again.", Notification.Type.ERROR_MESSAGE);
                        dataElements = subtreeFilter.isEmpty() ? session.getConfig(Netconf.Datastore.RUNNING, command) :
                                session.getConfig(Netconf.Datastore.RUNNING, subtreeFilter, command);
                    }
                    dataQuery = newQuery;
                } else {
                    dataElements = subtreeFilter.isEmpty() ? session.getConfig(dataSource, command) :
                                session.getConfig(dataSource, subtreeFilter, command);
                }
            } catch (NetconfException e) {
                e.printStackTrace();
                Notification.show("Failed to get data: " + e.getMessage(), Notification.Type.ERROR_MESSAGE);
            }
        }

        // Collect NETCONF data for tree display
        TreeData<XMLElement> data = new TreeData<>();
        for (XMLElement element: dataElements)
            addXMLToTree(data, element, null, moduleQuery, fieldQuery);

        // Create data provider for tree and define sorting order
        TreeDataProvider<XMLElement> dataProvider = new TreeDataProvider<>(data);
        dataProvider.setSortComparator(Comparator.comparing(XMLElement::getName)::compare);
        dataTree.setDataProvider(dataProvider);

        int remain = 100;

        // Expand up to 50 direct filter matches from data tree
        if (moduleFilter.isEmpty() && fieldFilter.isEmpty()) {
            for (WrappedYangNode node : schemaTree.getSelectedItems()) {
                String path = node.getSensorPath(false, null);
                List<String> paths = Arrays.asList(path.substring(path.indexOf(':') + 1).split("/"));
                remain = expandXMLSelected(dataTree, data.getRootItems(), paths, remain);
            }
        }

        for (XMLElement element: data.getRootItems())
            remain = applyXMLExpanded(dataTree, element, remain);

        if (remain <= 0)
            Notification.show("Too many results! They are all shown, but only 100 have been auto-expanded.",
                    Notification.Type.TRAY_NOTIFICATION);

        return dataTree;
    }

    // Transform XML data to a Vaadin treedata object
    private static boolean addXMLToTree(TreeData<XMLElement> data, XMLElement element, XMLElement parent,
                                 Collection<String> nodeQuery, Collection<String> valueQuery) {
	    String name = element.getName().toLowerCase();
        boolean nodeOkay = nodeQuery.stream().filter(name::contains).count() == nodeQuery.size();
        boolean valueOkay = valueQuery.isEmpty();
        boolean okay = false;

        // Add element to tree
        data.addItem(parent, element);

        // Add dummy XML attributes to mark expansion of nodes based on filters
        if (parent == null)
            element.withAttribute("root", "1");
        else if (!nodeQuery.isEmpty() || !valueQuery.isEmpty())
            parent.withAttribute("expand", "1");

        // Once we have a match for node filter, we want all children to be visible, so clear node filter when recursing
        if (nodeOkay && !nodeQuery.isEmpty())
            nodeQuery = Collections.emptyList();

        // For value filter expand child nodes with matching terms
        for (XMLElement child: element) {
            String childText = child.stream().findAny().isPresent() ? null : child.getText().toLowerCase();
            if (childText != null && !valueQuery.isEmpty() &&
                    valueQuery.stream().filter(childText::contains).count() == valueQuery.size()) {
                element.withAttribute("expand", "1");
                valueQuery = Collections.emptyList();
                break;
            }
        }

        // Recurse for each child
        for (XMLElement child: element)
            if (addXMLToTree(data, child, element, nodeQuery, valueQuery))
                okay = true;

        okay = okay || (valueOkay && nodeOkay);

        // If we are filtered by node or value filter and none of our children are visible, remove ourselve
        if (!okay)
            data.removeItem(element);

        return okay;
    }

    // Recursively apply element expansion to a tree based on meta-attributes set by addXMLToTree
    private static int applyXMLExpanded(Tree<XMLElement> tree, XMLElement element, int limit) {
	    if (element.getAttribute("expand").equals("1") && limit > 0) {
            int limitBefore = limit;
            tree.expand(element);

            for (XMLElement child: tree.getTreeData().getChildren(element))
                limit = applyXMLExpanded(tree, child, limit);

            if (limit == limitBefore)
                --limit;
        }
        return limit;
    }

    // Apply YANG schema filters to data tree
    private static int expandXMLSelected(Tree<XMLElement> tree, Iterable<XMLElement> elements, List<String> path, int limit) {
	    if (path.size() < 1 || limit < 1)
	        return limit;

	    path = new LinkedList<>(path);
        String hop = path.remove(0);
        for (XMLElement element: elements) {
            int limitBefore = limit;
            if (element.getName().equals(hop)) {
                tree.expand(element);
                limit = expandXMLSelected(tree, tree.getTreeData().getChildren(element), new LinkedList<>(path), limit);
            }
            if (limit == limitBefore)
                --limit;
        }
        return limit;
    }

    // Render home view
    private void showHomeScreen() {
	    sidebarPanel.removeAllComponents();

        VerticalLayout warningLayout = new VerticalLayout();
        for (String warning: parser.getWarnings()) {
            Label warningLabel = new Label(warning);
            warningLabel.addStyleName(ValoTheme.LABEL_FAILURE);
            warningLayout.addComponent(warningLabel);
        }
        Panel warningPanel = new Panel("Parser Warnings", warningLayout);
        warningPanel.setHeight(200, Unit.PIXELS);
        sidebarPanel.addComponent(warningPanel);
    }

    // Show detail table for a selected YANG schema node
    void showYangNode(WrappedYangNode node) {
	    selectedNode = node;
        sidebarPanel.removeAllComponents();

        if (gnmiTools != null)
            gnmiTools.updateNode(node);

        LinkedList<AbstractMap.SimpleEntry<String,String>> parameters = new LinkedList<>();
        parameters.add(new AbstractMap.SimpleEntry<>("Name", node.getName()));
        parameters.add(new AbstractMap.SimpleEntry<>("Namespace", node.getNamespace()));
        parameters.add(new AbstractMap.SimpleEntry<>("Type", node.getType() + " (" +
                        (node.isConfiguration() ? "configuration" : "operational") + ")"));

        String type = node.getDataType();
        if (!type.isEmpty())
            parameters.add(new AbstractMap.SimpleEntry<>("Data Type", type));

        String keys = node.getKeys().collect(Collectors.joining(" "));
        if (!keys.isEmpty())
            parameters.add(new AbstractMap.SimpleEntry<>("Keys", keys));

        parameters.add(new AbstractMap.SimpleEntry<>("XPath", node.getXPath()));
        parameters.add(new AbstractMap.SimpleEntry<>("Sensor Path", node.getSensorPath(false, null)));
        parameters.add(new AbstractMap.SimpleEntry<>("Filter Path", node.getSensorPath(true, selectedData)));
        parameters.add(new AbstractMap.SimpleEntry<>("Maagic Path", node.getMaagic(false)));
        parameters.add(new AbstractMap.SimpleEntry<>("Maagic QPath", node.getMaagic(true)));

        Grid<AbstractMap.SimpleEntry<String,String>> parameterGrid = new Grid<>("Parameters");
        parameterGrid.addColumn(AbstractMap.SimpleEntry::getKey).setCaption("Name");
        parameterGrid.addColumn(AbstractMap.SimpleEntry::getValue).setCaption("Value");
        parameterGrid.setItems(parameters);
        parameterGrid.setHeightMode(HeightMode.UNDEFINED);
        parameterGrid.setWidth("100%");
        sidebarPanel.addComponent(parameterGrid);

        descriptionLabel.setValue(node.getDescription());

        TextArea subtreeFilter = new TextArea("Subtree Filter");
        subtreeFilter.addValueChangeListener(event -> {
            String content = event.getValue();
            int numLines = content.split("\r\n|\r|\n").length + 1;
            int desiredRows = Math.max(numLines, 5);
            desiredRows = Math.min(desiredRows, 10);
            subtreeFilter.setRows(desiredRows);
        });
        node.createNetconfTemplate().map(XMLElement::toString).ifPresent(subtreeFilter::setValue);
        subtreeFilter.setReadOnly(true);
        subtreeFilter.setWidth("100%");
        sidebarPanel.addComponent(subtreeFilter);
    }

    public boolean searchModels(String moduleFilter, String nodeFilter) {
        Tree<WrappedYangNode> tree = showSchemaTree(moduleFilter, nodeFilter);
        treePanel.setContent(tree);
        return tree.getTreeData().getRootItems().size() > 0;
    }

    @Override
    public void enter(ViewChangeEvent event) {

    }

    @Override
    public void beforeLeave(ViewBeforeLeaveEvent event) {
	    try {
            client.close();
        } catch (NetconfException e) {
	        e.printStackTrace();
        }
    }

    private void refreschSchemas() {

      UI ui = UI.getCurrent();

      // Render loading window
      Window loadingWindow = new Window();
      loadingWindow.setModal(true);
      loadingWindow.setResizable(false);
      loadingWindow.setClosable(false);
      loadingWindow.setDraggable(false);
      loadingWindow.setWidth("900px");
      loadingWindow.setHeight("75px");

      HorizontalLayout layout = new HorizontalLayout();
      layout.setMargin(true);
      layout.setSizeFull();

      ProgressBar progressBar = new ProgressBar();
      progressBar.setIndeterminate(true);
      progressBar.setWidth("150px");
      Label label = new Label("Connecting...");
      label.addStyleName(ValoTheme.LABEL_BOLD);
      layout.addComponents(progressBar, label);
      layout.setComponentAlignment(progressBar, Alignment.MIDDLE_LEFT);
      layout.setComponentAlignment(label, Alignment.MIDDLE_LEFT);
      layout.setExpandRatio(label, 1.0f);

      loadingWindow.setContent(layout);
      ui.addWindow(loadingWindow);
      ui.push();

      NetconfYangParser yangParser = new NetconfYangParser();
      progressBar.setIndeterminate(false);

      yangParser.setCacheDirectory(new File("..", "yangcache").toString());

      try (NetconfSession session = this.client.createSession()) {
        Map<String, String> schemas = yangParser.getAvailableSchemas(session);
        // ui.capabilities = session.getCapabilities();

        yangParser.retrieveSchemas(session, schemas, (iteration, identifier, version, error) -> {
          label.setValue(String.format("Retrieving schema %s@%s: %s",
              identifier, version, (error != null) ? error.getMessage() : "success"));
          progressBar.setValue(((float) iteration) / schemas.size());
          ui.push();
        }, true);

        // Actually parse the YANG models using ODL yangtools
        label.setValue(String.format("Parsing schemas. This may take a minute..."));
        progressBar.setIndeterminate(true);
        ui.push();

        yangParser.parse();

        if (yangParser.getSchemaContext() == null) {
          Notification.show("Failed to parse schemas: no valid YANG models found!",
              Notification.Type.ERROR_MESSAGE);
        }
      } catch (Exception e) {
        Notification.show(
            "Failed to retrieve schemas: " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()),
            Notification.Type.ERROR_MESSAGE);
        e.printStackTrace();
      }

      loadingWindow.close();
      ui.removeWindow(loadingWindow);
    }

    public void setMessageCallback(MessageCallback callback) {
      this.callback = callback;
    }
}
