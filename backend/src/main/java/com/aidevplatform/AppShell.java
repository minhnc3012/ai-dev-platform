package com.aidevplatform;

import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.component.page.Push;

/**
 * Enables Vaadin server push (WebSocket) for the entire application.
 *
 * Without @Push, UI.access() calls from background threads queue updates but
 * nothing delivers them to the browser until the next client-initiated request.
 * With @Push, Vaadin immediately pushes queued updates over WebSocket as soon
 * as UI.access() runs — giving real-time agent status updates in the monitor.
 */
@Push
public class AppShell implements AppShellConfigurator {
}
