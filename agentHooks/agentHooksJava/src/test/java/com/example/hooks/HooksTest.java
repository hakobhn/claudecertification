package com.example.hooks;

import com.example.hooks.Hooks.Outcome;
import com.example.hooks.Tools.Session;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Offline tests: the hooks are plain code, so they can be tested without the model. */
class HooksTest {

    static final Map<String, Object> REFUND = Map.of("order_id", "O-7001", "amount", 900.0);
    static final Map<String, Object> TRANSFER = Map.of("order_id", "O-7002", "sender_id", "S-88", "amount", 900.0);

    @SuppressWarnings("unchecked")
    static Map<String, Object> result(String tool, Map<String, Object> args, Session s) {
        return (Map<String, Object>) Hooks.runWithHooks(tool, args, s).content();
    }

    @Test
    void postToolUseTurnsAllThreeFormatsIntoIsoDatesAndEnglishStatuses() {
        Session s = new Session();
        Map<String, Object> customer = result("get_customer", Map.of("customer_id", "C-1001"), s);
        Map<String, Object> order = result("lookup_order", Map.of("order_id", "O-7002"), s);
        Map<String, Object> shipping = result("check_shipping", Map.of("order_id", "O-7002"), s);
        Map<String, Object> lost = result("check_shipping", Map.of("order_id", "O-7001"), s);

        assertEquals("2024-03-15T08:00:00Z", customer.get("created_at"));  // was 1710489600
        assertEquals("active", customer.get("status"));                   // was 200
        assertEquals("2024-03-15T12:00:00Z", order.get("placed_at"));     // already ISO: unchanged
        assertEquals("active", order.get("status"));
        assertEquals("2024-04-03T00:00:00Z", shipping.get("shipped_on")); // was 03/04/2024 = 3 April
        assertEquals("delivered", shipping.get("status"));                // was "D"
        assertEquals("lost in transit", lost.get("status"));              // was "L"
    }

    @Test
    void preToolUseDeniesRefundOver500BeforeTheToolRuns() {
        Session s = new Session();
        Outcome out = Hooks.runWithHooks("process_refund", REFUND, s);
        assertTrue(out.isError());
        assertTrue(((String) out.content()).contains("staff approval"));
        assertFalse(s.executed.contains("process_refund"));    // the tool NEVER ran
        assertEquals(1, s.escalations.size());                  // redirected to a human
    }

    @Test
    void preToolUseAllowsRefundUpTo500() {
        Session s = new Session();
        assertFalse(Hooks.runWithHooks("process_refund", Map.of("order_id", "O-7001", "amount", 500.0), s).isError());
        assertTrue(s.executed.contains("process_refund"));
    }

    @Test
    void preToolUseDeniesTransferUntilAmlPasses() {
        Session s = new Session();
        Outcome out = Hooks.runWithHooks("transfer_funds", TRANSFER, s);
        assertTrue(out.isError());
        assertTrue(((String) out.content()).contains("aml_check"));  // tells the model what to do
        assertFalse(s.executed.contains("transfer_funds"));

        Hooks.runWithHooks("aml_check", Map.of("sender_id", "S-88"), s); // PostToolUse hook sets amlPassed
        assertTrue(s.amlPassed);
        assertFalse(Hooks.runWithHooks("transfer_funds", TRANSFER, s).isError());
        assertEquals(List.of("aml_check", "transfer_funds"), s.executed);
    }
}
