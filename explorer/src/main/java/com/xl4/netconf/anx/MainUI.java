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

import javax.servlet.annotation.WebServlet;

import com.xl4.netconf.anc.NetconfSSHClient;
import com.vaadin.annotations.Push;
import com.vaadin.annotations.Theme;
import com.vaadin.annotations.VaadinServletConfiguration;
import com.vaadin.server.Page;
import com.vaadin.server.VaadinRequest;
import com.vaadin.server.VaadinServlet;
import com.vaadin.ui.themes.ValoTheme;

import java.util.Map;

/**
 * This MainUI is the application entry point. A MainUI may either represent a browser window
 * (or tab) or some part of a html page where a Vaadin application is embedded.
 * <p>
 * The MainUI is initialized using {@link #init(VaadinRequest)}. This method is intended to be
 * overridden to add component to the user interface and initialize non-component functionality.
 */

@Theme("adtportal")
@Push
public class MainUI extends com.vaadin.ui.UI implements MessageCallback {
    String name;
    String username;
    String password;
    NetconfSSHClient client;
    NetconfYangParser parser;
    Map<String,String> capabilities;

    MainView main;
    SalesView sales; 

    @Override
    protected void init(VaadinRequest vaadinRequest) {
    	Page.getCurrent().setTitle("Excelfore Yang Explorer");
        addStyleName(ValoTheme.UI_WITH_MENU);
        
        setContent(new RetrieverView(this, vaadinRequest));
        addStyleName("loginview");
    }

    public void showMain() {
        main = new MainView(name, username, password, client, parser, capabilities);
        main.setMessageCallback(this);

        sales = new SalesView(name, username, password, client, parser);
        sales.setMessageCallback(this);

        // Show main view or login view depending on state
        setSizeFull();
        getPage().setTitle("Netconf: ".concat(name));
        setContent(sales);
        removeStyleName("loginview");
        addStyleName("mainview");
    }

    @WebServlet(urlPatterns = "/*", name = "MyUIServlet", asyncSupported = true)
    @VaadinServletConfiguration(ui = MainUI.class, productionMode = true)
    public static class MyUIServlet extends VaadinServlet {
    }

    @Override
    public void onMessageReceived(String message) {
        if (message.equals("main")) {
          setContent(main);
        } else {
          setContent(sales);
        }
    }
}

interface MessageCallback {
    void onMessageReceived(String message);
}