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

import com.xl4.netconf.anc.Netconf;
import com.xl4.netconf.anc.NetconfException;
import com.xl4.netconf.anc.NetconfSession;
import com.xl4.netconf.anc.XMLElement;
import com.vaadin.icons.VaadinIcons;
import com.vaadin.ui.*;
import com.vaadin.ui.Upload.Receiver;
import com.vaadin.ui.Upload.SucceededEvent;
import com.vaadin.ui.Upload.SucceededListener;
import com.vaadin.ui.themes.ValoTheme;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * NETCONF console utilities to run manual netconf commands
 */
public class NetconfTools {
    private MainView view;
    private SalesView sView;
    private TextArea requestArea;

    NetconfTools(MainView view) throws NetconfException {
        this.view = view;
    }

    NetconfTools(SalesView view) throws NetconfException {
        this.sView = view;
    }

    Component createComponent() {
        HorizontalLayout netconfTools = new HorizontalLayout();
        netconfTools.setSizeUndefined();
        netconfTools.setDefaultComponentAlignment(Alignment.MIDDLE_LEFT);

        Button getCommand = new Button("NETCONF console", VaadinIcons.TOOLS);
        getCommand.addStyleName(ValoTheme.BUTTON_FRIENDLY);
        getCommand.addClickListener(x -> showWindow());
        netconfTools.addComponent(getCommand);
        return netconfTools;
    }

    void showWindow() {
        NetconfSession session;
        Window window = new Window("NETCONF console");
        window.setModal(true);
        window.setWidth("1000px");
        window.setHeight("700px");

        try {
            session = sView != null ? sView.client.createSession() : view.client.createSession();

            window.addCloseListener(x -> {
                try {
                    session.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        } catch (NetconfException e) {
            Notification.show(e.getMessage(), Notification.Type.ERROR_MESSAGE);
            return;
        }

        VerticalLayout layout = new VerticalLayout();
        layout.setMargin(true);
        layout.setSizeFull();

        HorizontalLayout buttonLayout = new HorizontalLayout();
        Label prepareLabel = new Label("Prepare: ");
        buttonLayout.addComponent(prepareLabel);
        buttonLayout.setComponentAlignment(prepareLabel, Alignment.MIDDLE_LEFT);
        Button prepareGet = new Button("<get>", VaadinIcons.ARROW_CIRCLE_DOWN);
        buttonLayout.addComponent(prepareGet);
        Button prepareEdit = new Button("<edit-config> (Merge)", VaadinIcons.EDIT);
        buttonLayout.addComponent(prepareEdit);
        Button prepareDelete = new Button("<edit-config> (Delete)", VaadinIcons.FILE_REMOVE);
        buttonLayout.addComponent(prepareDelete);
        Button prepareCommit = new Button("<commit>", VaadinIcons.CHECK_CIRCLE);
        buttonLayout.addComponent(prepareCommit);
        Upload upload = new Upload(null, new XMLUploader());
        upload.setButtonCaption("Load");
        upload.setAcceptMimeTypes("application/xml, text/xml");
        upload.addSucceededListener(new XMLUploadSucceededListener());
        buttonLayout.addComponent(upload);

        layout.addComponent(buttonLayout);

        requestArea = new TextArea();
        requestArea.setSizeFull();
        layout.addComponent(requestArea);
        layout.setExpandRatio(requestArea, 1.0f);

        HorizontalLayout submitLayout = new HorizontalLayout();
        Button submit = new Button("Send Request", VaadinIcons.ARROW_FORWARD);
        submitLayout.addComponent(submit);
        layout.addComponent(submitLayout);

        TextArea responseArea = new TextArea();
        responseArea.setSizeFull();
        layout.addComponent(responseArea);
        layout.setExpandRatio(responseArea, 1.0f);

        requestArea.addValueChangeListener(x -> responseArea.clear());

        submit.addClickListener(x -> {
            try {
                responseArea.setValue(session.call(new XMLElement(requestArea.getValue())).stream()
                        .map(XMLElement::toString).collect(Collectors.joining("\n")));
            } catch (NetconfException.RPCException e) {
                responseArea.setValue(e.getRPCReply().stream()
                        .map(XMLElement::toString).collect(Collectors.joining("\n")));
            } catch (Exception e) {
                Notification.show(e.getMessage(), Notification.Type.ERROR_MESSAGE);
            }
        });

        prepareGet.addClickListener(x -> requestArea.setValue(
                new XMLElement(Netconf.NS_NETCONF, "get", r -> Optional.ofNullable(view != null ? view.selectedNode : sView.selectedNode)
                        .flatMap(node -> node.createNetconfTemplate(null, view != null ? view.selectedData : sView.selectedData))
                        .ifPresent(r.createChild("filter")::withChild)).toString()));

        prepareEdit.addClickListener(x -> {
            XMLElement pattern = new XMLElement(Netconf.NS_NETCONF, "edit-config");
            pattern.createChild("target").withChild("running");
            Optional.ofNullable(view != null ? view.selectedNode : sView.selectedNode).flatMap(n -> n.createNetconfTemplate("", view != null ? view.selectedData : sView.selectedData))
                    .ifPresent(f -> pattern.createChild("config").withChild(f));
            requestArea.setValue(pattern.toString());
        });

        prepareDelete.addClickListener(x -> {
            XMLElement pattern = new XMLElement(Netconf.NS_NETCONF, "edit-config");
            pattern.createChild("target").withChild("running");
            pattern.withTextChild("default-operation", "none");
            Optional.ofNullable(view != null ? view.selectedNode : sView.selectedNode).flatMap(n -> n.createNetconfTemplate("delete", view != null ? view.selectedData : sView.selectedData))
                    .ifPresent(f -> pattern.createChild("config").withChild(f));
            requestArea.setValue(pattern.toString());
        });

        prepareCommit.addClickListener(x -> requestArea.setValue(
                new XMLElement(Netconf.NS_NETCONF, "commit").toString()));

        window.setContent(layout);
        UI.getCurrent().addWindow(window);
    }

    private class XMLUploader implements Receiver {
        private ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        @Override
        public OutputStream receiveUpload(String filename, String mimeType) {
            return outputStream;
        }

        public byte[] getUploadedData() {
            return outputStream.toByteArray();
        }

        public void clear() {
          outputStream.reset();
        }
    }

    private class XMLUploadSucceededListener implements SucceededListener {

        @Override
        public void uploadSucceeded(SucceededEvent event) {
            byte[] uploadedData = ((XMLUploader) event.getUpload().getReceiver()).getUploadedData();
            requestArea.clear();
            requestArea.setValue(new String(uploadedData));
            ((XMLUploader) event.getUpload().getReceiver()).clear();
        }
    }
}
