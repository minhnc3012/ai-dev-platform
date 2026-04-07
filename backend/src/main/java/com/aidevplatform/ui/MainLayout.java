package com.aidevplatform.ui;

import com.aidevplatform.api.dto.AgentEventDto;
import com.aidevplatform.service.SseService;
import com.aidevplatform.ui.views.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.applayout.DrawerToggle;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.sidenav.SideNav;
import com.vaadin.flow.component.sidenav.SideNavItem;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.theme.lumo.LumoUtility;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Main application layout with navigation drawer and header.
 * Referenced by @Route(layout = MainLayout.class) on all views.
 */
@RequiredArgsConstructor
@Slf4j
public class MainLayout extends AppLayout {

    private final SseService sseService;

    @Override
    protected void onAttach(AttachEvent event) {
        super.onAttach(event);
        createHeader();
        createNavigation();
    }

    private void createHeader() {
        DrawerToggle toggle = new DrawerToggle();

        H1 title = new H1("AI Dev Platform");
        title.addClassNames(LumoUtility.FontSize.LARGE, LumoUtility.Margin.NONE);

        Span version = new Span("v1.0");
        version.addClassNames(LumoUtility.FontSize.SMALL,
                LumoUtility.TextColor.SECONDARY,
                LumoUtility.Margin.Left.SMALL);

        HorizontalLayout header = new HorizontalLayout(toggle, title, version);
        header.setAlignItems(FlexComponent.Alignment.CENTER);
        header.setWidthFull();
        header.setSpacing(false);
        header.addClassNames(LumoUtility.Padding.Horizontal.MEDIUM);

        addToNavbar(header);
    }

    private void createNavigation() {
        SideNav nav = new SideNav();
        nav.addItem(new SideNavItem("Dashboard", DashboardView.class, createIcon("dashboard")));
        nav.addItem(new SideNavItem("Projects", ProjectListView.class, createIcon("folder")));
        nav.setWidthFull();

        addToDrawer(nav);
    }

    private Span createIcon(String iconName) {
        Span icon = new Span();
        icon.addClassNames("la", "la-" + iconName);
        return icon;
    }

    /**
     * Returns the current user ID from the session.
     * Used for SSE subscription and permission checks.
     */
    public static String getCurrentUserId() {
        Object userId = VaadinSession.getCurrent().getAttribute("userId");
        return userId != null ? userId.toString() : "anonymous";
    }
}
