-- MySQL 8.0.16+ enforces CHECK constraints. The application already refuses non-positive amounts;
-- this makes the database refuse them too, whichever code path (or manual SQL) writes the row.
ALTER TABLE expense
    ADD CONSTRAINT chk_expense_amount_positive CHECK (amount > 0);
