package com.tesla.simulation;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CardSimulationPersistenceAspect {

    private final CardSimulationDatabase database;
    private final TransactionTemplate transactionTemplate;

    public CardSimulationPersistenceAspect(
            CardSimulationDatabase database,
            TransactionTemplate transactionTemplate) {
        this.database = database;
        this.transactionTemplate = transactionTemplate;
    }

    @Around("execution(public * com.tesla.simulation.CardSimulationService.*(..))")
    public Object persistLifecycleState(ProceedingJoinPoint joinPoint) throws Throwable {
        CardSimulationService service = (CardSimulationService) joinPoint.getTarget();
        synchronized (service) {
            return transactionTemplate.execute(status -> {
                database.load(service);
                Object result;
                try {
                    result = joinPoint.proceed();
                } catch (RuntimeException | Error exception) {
                    throw exception;
                } catch (Throwable exception) {
                    throw new IllegalStateException("Card lifecycle request failed.", exception);
                }
                if (!isReadOnly(joinPoint.getSignature().getName())) {
                    database.save(service);
                }
                return result;
            });
        }
    }

    private boolean isReadOnly(String methodName) {
        return switch (methodName) {
            case "account", "card", "transaction", "accountTransactions", "reconcile" -> true;
            default -> false;
        };
    }
}
