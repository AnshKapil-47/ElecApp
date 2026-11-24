---
paths:
  - "web/**"
---

# Web app rules (Owner and Subowner only)

- Only OWNER and SUBOWNER log in on the web. Agents, Customers and Subusers use the mobile app; the backend and the database reject their web sessions.
- **Owner screens:**
  - Subowners, Agents and Customers (all);
  - dataset import and delete;
  - direct entitlements, void sale, and the mobile-number switch per entitlement;
  - owning-Agent reassignment;
  - Customer credential and device actions;
  - activation-code re-issue;
  - all sales reports.
- **Subowner screens:**
  - Agents and Customers (all; contact phone masked);
  - dataset import and delete;
  - direct entitlements;
  - suspend and reactivate;
  - activation-code re-issue.
  - **No** sales reports, void, mobile switch, or Customer password and device actions.
- No voter search screens for any web role.
- No price, payment or commission fields anywhere.
- No notification UI. "Expiring soon" is a filtered list.
- Hiding something in the UI is convenience only; the backend checks every action.
