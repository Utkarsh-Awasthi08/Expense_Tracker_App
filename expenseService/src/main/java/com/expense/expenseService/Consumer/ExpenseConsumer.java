package com.expense.expenseService.Consumer;

import com.expense.expenseService.DTO.ExpenseDTO;
import com.expense.expenseService.Service.ExpenseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
public class ExpenseConsumer {

    private static final Logger log = LoggerFactory.getLogger(ExpenseConsumer.class);

    private final ExpenseService expenseService;

    @Autowired
    ExpenseConsumer(ExpenseService expenseService) {
        this.expenseService = expenseService;
    }

    // rewritten in the ingestion stage (expense.parsed.v1 contract, idempotent insert, dead-letter topic).
    // Until then the old 3-field payload cannot satisfy the NOT NULL user_id / txn_date columns, so a message
    // that is not stored is logged at ERROR instead of being dropped silently.
    @KafkaListener(topics = "${spring.kafka.topic-json.name}", groupId  = "${spring.kafka.consumer.group-id}")
    public void consume(ExpenseDTO expenseDTO) {
        if (expenseDTO == null) {
            log.error("Expense message dropped: empty payload");
            return;
        }
        boolean stored = false;
        try {
            stored = expenseService.createExpense(expenseDTO);
        } catch (Exception e) {
            log.error("Expense message dropped: unexpected failure (user_id={}, external_id={}, sms_hash={})",
                    expenseDTO.getUserId(), expenseDTO.getExternalId(), expenseDTO.getSmsHash(), e);
            return;
        }
        if (!stored) {
            log.error("Expense message dropped, NOT stored (user_id={}, external_id={}, sms_hash={}); see the preceding rejection reason",
                    expenseDTO.getUserId(), expenseDTO.getExternalId(), expenseDTO.getSmsHash());
        }
    }

}
