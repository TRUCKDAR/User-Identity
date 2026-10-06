package escuelaing.edu.co.truckdar.User_Identity.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Implementación local del publicador de eventos — registra los eventos en el log.
 * <p>
 * Esta implementación se reemplazará por un productor de Kafka cuando se integre
 * el bus de eventos compartido (Amazon MSK).
 * </p>
 */
@Component
public class LoggingDomainEventPublisher implements DomainEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingDomainEventPublisher.class);

    @Override
    public void publish(UserRegisteredEvent event) {
        log.info("[DOMAIN-EVENT] UserRegistered → userId={}, email={}, role={}",
                event.userId(), event.email(), event.role());
    }

    @Override
    public void publish(UserRoleChangedEvent event) {
        log.info("[DOMAIN-EVENT] UserRoleChanged → userId={}, oldRole={}, newRole={}",
                event.userId(), event.oldRole(), event.newRole());
    }
}
