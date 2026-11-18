Feature: Transfer form validation and a real transfer

  # Each scenario is written to stand on its own, the way a team would write it.
  # Mode A runs them that way. Mode B runs the same file as a prefix tree.

  Background:
    Given the app is launched unlocked
    And I remember the EUR balance
    And I open the transfer form

  # IOS-VAL-02, defect zeroAmountAccepted
  Scenario: A zero amount can't be sent
    When I enter recipient "Alex"
    And I enter amount "0"
    Then Continue is disabled

  # IOS-VAL-06, defect transferNegativeCredits
  Scenario: A negative amount can't be sent
    When I enter recipient "Alex"
    And I enter amount "-100"
    Then Continue is disabled

  # IOS-VAL-05, defect amountExceedsBalanceAllowed
  Scenario: An amount over the balance can't be sent
    When I enter recipient "Alex"
    And I enter amount "999999"
    Then Continue is disabled

  # IOS-VAL-03, defect whitespaceRecipient
  Scenario: A spaces-only recipient can't be sent to
    When I enter recipient "   "
    And I enter amount "100"
    Then Continue is disabled

  # Positive edge. Verified by fact: the EUR balance on Home, not the success toast.
  # Catches transferRoundsUp (debits 101) and transferDebitsWrongAccount (EUR unchanged).
  Scenario: A valid transfer debits exactly the amount
    When I enter recipient "Alex"
    And I enter amount "100.40"
    Then Continue is enabled
    When I continue and confirm
    Then the EUR balance dropped by 100.40
