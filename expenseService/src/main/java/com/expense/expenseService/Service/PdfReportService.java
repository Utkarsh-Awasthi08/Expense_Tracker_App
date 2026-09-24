package com.expense.expenseService.Service;

import com.expense.expenseService.Entities.Expense;
import com.expense.expenseService.Repository.ExpenseRepository;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class PdfReportService {

    private final ExpenseRepository expenseRepository;

    public PdfReportService(ExpenseRepository expenseRepository) {
        this.expenseRepository = expenseRepository;
    }

    public byte[] generateMonthlyReport(String userId, int year, int month) {
        LocalDate startDate = LocalDate.of(year, month, 1);
        LocalDate endDate = startDate.withDayOfMonth(startDate.lengthOfMonth());
        
        List<Expense> expenses = new java.util.ArrayList<>();
        expenseRepository.findAll().forEach(expenses::add);
        
        expenses = expenses.stream()
                .filter(e -> e.getUserId().equals(userId))
                .filter(e -> !e.getTxnDate().isBefore(startDate) && !e.getTxnDate().isAfter(endDate))
                .collect(Collectors.toList());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4);
        PdfWriter.getInstance(document, baos);
        document.open();

        Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18);
        Font tableHeaderFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, Color.WHITE);
        Font normalFont = FontFactory.getFont(FontFactory.HELVETICA, 10);

        Paragraph title = new Paragraph("Monthly Expense Report - " + startDate.getMonth() + " " + year, titleFont);
        title.setAlignment(Element.ALIGN_CENTER);
        document.add(title);
        
        document.add(new Paragraph(" "));

        BigDecimal total = expenses.stream()
                .map(Expense::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
                
        document.add(new Paragraph("Total Expenses: " + total, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14)));
        document.add(new Paragraph(" "));

        PdfPTable table = new PdfPTable(4);
        table.setWidthPercentage(100);
        
        String[] headers = {"Date", "Merchant", "Category", "Amount"};
        for (String header : headers) {
            PdfPCell cell = new PdfPCell(new Phrase(header, tableHeaderFont));
            cell.setBackgroundColor(Color.DARK_GRAY);
            cell.setPadding(5);
            table.addCell(cell);
        }

        for (Expense expense : expenses) {
            table.addCell(new Phrase(expense.getTxnDate().toString(), normalFont));
            table.addCell(new Phrase(expense.getMerchant() != null ? expense.getMerchant() : "-", normalFont));
            table.addCell(new Phrase(expense.getCategory(), normalFont));
            table.addCell(new Phrase(expense.getAmount().toString(), normalFont));
        }

        document.add(table);
        document.close();

        return baos.toByteArray();
    }
}
