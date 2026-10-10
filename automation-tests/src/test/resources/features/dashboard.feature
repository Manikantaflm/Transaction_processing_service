Feature: Transaction service operations landing page
  The browser suite checks the user-visible service entry point. Financial invariants are tested by
  PostgreSQL integration tests and API-level tests rather than by brittle browser-only assertions.

  Scenario: Landing page explains the service and its authenticated API
    Given the transaction service landing page is available
    Then the page title should be "Transaction Processing Service"
    And the service overview heading should be visible
    And the liveness endpoint link should be available
    And the API authentication notice should be visible

  Scenario: Liveness endpoint is reachable from the landing page
    Given the transaction service landing page is available
    Then the liveness endpoint should report healthy
