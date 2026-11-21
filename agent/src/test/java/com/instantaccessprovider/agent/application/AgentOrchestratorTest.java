// SPDX-FileCopyrightText: 2026 Dédale Solutions
// SPDX-License-Identifier: EUPL-1.2

package com.instantaccessprovider.agent.application;

import com.instantaccessprovider.agent.domain.model.RawIamEvent;
import com.instantaccessprovider.agent.domain.model.SourceType;
import com.instantaccessprovider.agent.domain.port.out.EventPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AgentOrchestratorTest {

    @Mock
    EventPublisher eventPublisher;

    @InjectMocks
    AgentOrchestrator orchestrator;

    @Test
    void handle_publishesEventExactlyOnce() {
        RawIamEvent event = RawIamEvent.of(
                "ad://corp.example.com",
                SourceType.ACTIVE_DIRECTORY,
                "4728-001",
                "<Event><EventID>4728</EventID></Event>",
                Instant.now()
        );

        orchestrator.handle(event);

        verify(eventPublisher, times(1)).publish(event);
    }

    @Test
    void handle_publishesSameEventObject() {
        RawIamEvent event = RawIamEvent.of(
                "syslog://fw-01",
                SourceType.SYSLOG,
                "syslog-999",
                "Jun  1 12:00:00 fw-01 sshd: Accepted password for user1",
                Instant.now()
        );

        orchestrator.handle(event);

        verify(eventPublisher).publish(event);
    }
}
