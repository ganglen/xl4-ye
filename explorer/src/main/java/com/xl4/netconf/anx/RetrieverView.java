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
import com.vaadin.event.ShortcutAction.KeyCode;
import com.vaadin.icons.VaadinIcons;
import com.vaadin.server.FileResource;
import com.vaadin.server.Responsive;
import com.vaadin.server.VaadinRequest;
import com.vaadin.server.VaadinService;
import com.vaadin.ui.*;
import com.vaadin.ui.Button.ClickListener;
import com.vaadin.ui.themes.ValoTheme;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.util.Map;
import java.util.Optional;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.security.SecureRandom;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * View for logging in, retrieving and parsing YANG models
 */
@SuppressWarnings("serial")
public class RetrieverView extends VerticalLayout {

	public RetrieverView(MainUI ui, VaadinRequest request) {
        String profilePath = "/var/cache/jetty9/profiles.xml";
        setSizeFull();

        // Properties properties = new Properties();
        // try (InputStream input = new FileInputStream("/etc/excelforeyangexplorer.conf")) {
        //     properties.load(input);
        //     yangcachePath = properties.getProperty("YANGCACHE_DIR", "/var/cache/jetty9/webapps/yangcache");
        //     profilePath = properties.getProperty("PROFILES_XML_FILE", "/var/cache/jetty9/profiles.xml");
        // } catch (IOException ex) {
        //     ui.parser.setCacheDirectory(new File("..", "yangcache").toString());
        // }

        // Render login form
        final VerticalLayout loginPanel = new VerticalLayout();
        loginPanel.addStyleName("v-login");
        loginPanel.setSizeUndefined();
        Responsive.makeResponsive(loginPanel);

        HorizontalLayout logo = new HorizontalLayout();
        logo.setSpacing(true);
        
        loginPanel.addComponent(logo);

        String basepath = VaadinService.getCurrent().getBaseDirectory().getAbsolutePath();
        FileResource resource = new FileResource(new File(basepath + "/WEB-INF/images/excelfore.png"));
        Image image = new Image("", resource);
        image.addStyleName("welcome");
        image.addStyleName(ValoTheme.LABEL_H1);

        Label welcome = new Label("Excelfore Yang Explorer");
        welcome.addStyleName("welcome");
        welcome.addStyleName(ValoTheme.LABEL_H1);
        
        HorizontalLayout labels = new HorizontalLayout(image, welcome);
        loginPanel.addComponent(labels);

        Label subtitle = new Label("YANG model and data explorer for NETCONF devices");
        subtitle.setSizeUndefined();
        subtitle.addStyleName(ValoTheme.LABEL_H4);
        subtitle.addStyleName(ValoTheme.LABEL_COLORED);
        loginPanel.addComponent(subtitle);

        HorizontalLayout fields = new HorizontalLayout();
        fields.setSpacing(true);
        fields.addStyleName("fields");

        final Button connect = new Button("Login");
        connect.addStyleName(ValoTheme.BUTTON_PRIMARY);
        connect.setClickShortcut(KeyCode.ENTER);

        final ComboBox<String> hostname = new ComboBox<>("NETCONF Host (optional :port)");
        hostname.addStyleName(ValoTheme.TEXTFIELD_INLINE_ICON);
        hostname.addStyleName("darkicon");
        Optional.ofNullable(request.getParameter("hostname")).ifPresent(hostname::setValue);
        hostname.focus();

        // Read profiles from file
        XMLElement profiles;
        try {
            profiles = new XMLElement(new FileInputStream(new File(profilePath)));
        } catch (IOException | XMLElement.XMLException e) {
            profiles = new XMLElement(null, "profiles");
        }
        hostname.setItems(profiles.find("profile/hostname").map(XMLElement::getText));

        XMLElement allProfiles = profiles;
        hostname.setNewItemProvider(x -> {
            allProfiles.createChild("profile").createChild("hostname").withText(x);
            hostname.setItems(allProfiles.find("profile/hostname").map(XMLElement::getText));
            hostname.setValue(x);
            return Optional.ofNullable(x);
        });

        final TextField username = new TextField("Username");
        username.setIcon(VaadinIcons.USER);
        username.addStyleName(ValoTheme.TEXTFIELD_INLINE_ICON);
        username.addStyleName("darkicon");
        Optional.ofNullable(request.getParameter("username")).ifPresent(username::setValue);

        final PasswordField password = new PasswordField("Password");
        password.setIcon(VaadinIcons.LOCK);
        password.addStyleName(ValoTheme.TEXTFIELD_INLINE_ICON);
        password.addStyleName("darkicon");
        Optional.ofNullable(request.getParameter("password")).ifPresent(password::setValue);

        fields.addComponents(hostname, username, password, connect);
        fields.setComponentAlignment(connect, Alignment.BOTTOM_LEFT);

        HorizontalLayout extraFields = new HorizontalLayout();
        extraFields.setSpacing(true);
        extraFields.addStyleName("fields");

        final CheckBox cacheModels = new CheckBox("Cache YANG models");
        cacheModels.setValue(true);

        final CheckBox remember = new CheckBox("Remember credentials");
        extraFields.addComponents(cacheModels, remember);

        
        // Apply profile credentials if selected
        XMLElement loadedProfiles = profiles;
        hostname.addValueChangeListener(x -> {
            Optional<XMLElement> profile = loadedProfiles.find(
                String.format("profile[hostname='%s']", hostname.getValue())).findAny();
            
            try {
              createKey("/var/cache/jetty9/webapps/key/xl4yangexplorer"); 
              String encryptionKey = readKey("/var/cache/jetty9/webapps/key/xl4yangexplorer"); 
              profile.flatMap(p -> p.getFirst("username")).map(XMLElement::getText).ifPresent(encryptedUsername -> {
                  try {
                      username.setValue(decryptPassword(encryptedUsername, encryptionKey));
                  } catch (Exception ex) {
                      ex.printStackTrace();
                  }
              });
              profile.flatMap(p -> p.getFirst("password")).map(XMLElement::getText).ifPresent(encryptedPassword -> {
                  try {
                      password.setValue(decryptPassword(encryptedPassword, encryptionKey));
                  } catch (Exception ex) {
                      ex.printStackTrace();
                  }
              });
            } catch (Exception ex) {
              profile.flatMap(p -> p.getFirst("username")).map(XMLElement::getText).ifPresent(username::setValue);
              profile.flatMap(p -> p.getFirst("password")).map(XMLElement::getText).ifPresent(password::setValue);
            }
            profile.ifPresent(p -> remember.setValue(true));
        });

        // Connect to device and retrieve YANG models
        connect.addClickListener((ClickListener) event -> {
            ui.name = "";
            ui.username = username.getValue();
            ui.password = password.getValue();
            int port = 0;

            // Save profiles (there should probably be some file locking here...)
            XMLElement savedProfiles;
            try {
                savedProfiles = new XMLElement(new FileInputStream(new File(profilePath)));
            } catch (IOException | XMLElement.XMLException e) {
                savedProfiles = new XMLElement(null, "profiles");
            }

            savedProfiles.find(String.format("profile[hostname='%s']", hostname.getValue()))
                .findAny().ifPresent(XMLElement::remove);

            if (remember.getValue()) {
              try {
                createKey("/var/cache/jetty9/webapps/key/xl4yangexplorer"); 
                String encryptionKey = readKey("/var/cache/jetty9/webapps/key/xl4yangexplorer"); 
                String encryptedUsername = encryptPassword(ui.username, encryptionKey);
                String encryptedPassword = encryptPassword(ui.password, encryptionKey);
                savedProfiles.createChild("profile")
                        .withTextChild("hostname", hostname.getValue())
                        .withTextChild("username", encryptedUsername)
                        .withTextChild("password", encryptedPassword);
              } catch (Exception ex) {
                savedProfiles.createChild("profile")
                        .withTextChild("hostname", hostname.getValue())
                        .withTextChild("username", ui.username)
                        .withTextChild("password", ui.password);
              }

            }

            try {
                savedProfiles.writeTo(new FileOutputStream(new File(profilePath)), true);
            } catch (IOException | XMLElement.XMLException e) {
                e.printStackTrace();
            }

            try {
                URI uri = new URI("http://" + hostname.getValue());
                if (uri.getHost() != null)
                    ui.name = uri.getHost();

                if (uri.getPort() <= 0) {
                    try (Socket socket = new Socket()) {
                        socket.connect(new InetSocketAddress(uri.getHost(), 830), 1000);
                        port = 830;
                    } catch (Exception e) {
                        try (Socket socket = new Socket()) {
                            socket.connect(new InetSocketAddress(uri.getHost(), 2022), 1000);
                            port = 2022;
                        } catch (Exception f) {
                            port = 22;
                        }
                    }
                } else {
                    port = uri.getPort();
                }
            } catch (Exception f) {

            }

            ui.client = new NetconfSSHClient(ui.name, port, username.getValue());
            ui.client.setPassword(password.getValue());
            ui.client.setStrictHostKeyChecking(false);
            ui.client.setTimeout(3600000);
            ui.client.setKeepalive(15000);

            // Render login window
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

            ui.parser = new NetconfYangParser();
            progressBar.setIndeterminate(false);

            if (cacheModels.getValue()) {
                String filePath = new File("/var/cache/jetty9/webapps/yangcache").toString();
                ui.parser.setCacheDirectory(filePath);
            }

            try (NetconfSession session = ui.client.createSession()) {
                Map<String, String> schemas = ui.parser.getAvailableSchemas(session);
                ui.capabilities = session.getCapabilities();

                ui.parser.retrieveSchemas(session, schemas, (iteration, identifier, version, error) -> {
                    label.setValue(String.format("Retrieving schema %s@%s: %s",
                            identifier, version, (error != null) ? error.getMessage() : "success"));
                    progressBar.setValue(((float)iteration) / schemas.size());
                    ui.push();
                }, false);
    
                // Actually parse the YANG models using ODL yangtools
                label.setValue(String.format("Parsing schemas. This may take a minute..."));
                progressBar.setIndeterminate(true);
                ui.push();
    
                ui.parser.parse();
    
                if (ui.parser.getSchemaContext() != null)
                    ui.showMain();
                else
                    Notification.show("Failed to parse schemas: no valid YANG models found!",
                            Notification.Type.ERROR_MESSAGE);    
            } catch (Exception e) {
                Notification.show("Failed to retrieve schemas: " + (e.getCause() != null ?
                        e.getCause().getMessage() : e.getMessage()), Notification.Type.ERROR_MESSAGE);
                e.printStackTrace();
            }

            loadingWindow.close();
            ui.removeWindow(loadingWindow);
        });
        loginPanel.addComponent(fields);
        loginPanel.addComponent(extraFields);

		addComponent(loginPanel);
		setComponentAlignment(loginPanel, Alignment.MIDDLE_CENTER);
		setExpandRatio(loginPanel, 1.0f);
	}
  
  private static String encryptPassword(String password, String encryptionKey) throws Exception {
      SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
      createKey("/var/cache/jetty9/webapps/salt/xl4yangexplorer"); 
      String saltKey = readKey("/var/cache/jetty9/webapps/salt/xl4yangexplorer"); 
      byte[] salt = saltKey.getBytes();
      PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 65536, 256);
      SecretKey secretKey = factory.generateSecret(spec);
      SecretKeySpec secret = new SecretKeySpec(secretKey.getEncoded(), "AES");
      
      Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
      cipher.init(Cipher.ENCRYPT_MODE, secret);
      byte[] encryptedBytes = cipher.doFinal(password.getBytes());
      return Base64.getEncoder().encodeToString(encryptedBytes);
  }

  private static String decryptPassword(String encryptedPassword, String encryptionKey) throws Exception {
      byte[] encryptedBytes = Base64.getDecoder().decode(encryptedPassword);
      SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
      createKey("/var/cache/jetty9/webapps/salt/xl4yangexplorer"); 
      String saltKey = readKey("/var/cache/jetty9/webapps/salt/xl4yangexplorer"); 
      byte[] salt = saltKey.getBytes(); 
      PBEKeySpec spec = new PBEKeySpec(encryptionKey.toCharArray(), salt, 65536, 256);
      SecretKey secretKey = factory.generateSecret(spec);
      SecretKeySpec secret = new SecretKeySpec(secretKey.getEncoded(), "AES");
      
      Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
      cipher.init(Cipher.DECRYPT_MODE, secret);
      byte[] decryptedBytes = cipher.doFinal(encryptedBytes);
      return new String(decryptedBytes);
  }

  private static void createKey(String filePath) throws IOException {
      byte[] key = new byte[32];
      SecureRandom secureRandom = new SecureRandom();
      secureRandom.nextBytes(key);
      if (!Files.exists(Paths.get(filePath))) {
          Files.write(Paths.get(filePath), key, StandardOpenOption.CREATE);
      } 
      Files.setPosixFilePermissions(Paths.get(filePath), PosixFilePermissions.fromString("rw-------"));
  }

  public static String readKey(String filePath) throws IOException {
      byte[] keyBytes = Files.readAllBytes(Paths.get(filePath));
      return new String(keyBytes);
  }
}
