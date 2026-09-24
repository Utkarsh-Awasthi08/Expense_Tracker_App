import { StyleSheet, View, TouchableOpacity, ActivityIndicator } from 'react-native';
import React, { useMemo, useState } from 'react';
import CustomText from '../components/CustomText';
import { ExpenseDto } from './dto/ExpenseDto';
import { theme } from '../theme/theme';
import { Download } from 'lucide-react-native';
import ReactNativeBlobUtil from 'react-native-blob-util';
import Share from 'react-native-share';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { API_BASE_URL } from '../api/config';

interface SpendsInsightsProps {
  expenses: ExpenseDto[];
  budget: number | null;
  spent: number;
}

const SpendsInsights: React.FC<SpendsInsightsProps> = ({ expenses, budget, spent }) => {
  const [exporting, setExporting] = useState(false);

  const insightsData = useMemo(() => {
    let mostSpendCategory = 'N/A';
    let highestTransaction = 0;

    if (expenses && expenses.length > 0) {
      const categoryTotals: Record<string, number> = {};
      expenses.forEach(exp => {
        const cat = exp.category || 'OTHER';
        categoryTotals[cat] = (categoryTotals[cat] || 0) + exp.amount;
        if (exp.amount > highestTransaction) {
          highestTransaction = exp.amount;
        }
      });

      let maxAmount = 0;
      Object.entries(categoryTotals).forEach(([cat, total]) => {
        if (total > maxAmount) {
          maxAmount = total;
          mostSpendCategory = cat;
        }
      });
    }

    let status = 'Good';
    if (budget && budget > 0) {
      if (spent >= budget) status = 'Over Budget 🚨';
      else if (spent >= budget * 0.8) status = 'At Risk ⚠️';
    }

    return [
      { id: '1', label: 'Status', value: status },
      { id: '2', label: 'Highest Txn', value: highestTransaction > 0 ? highestTransaction.toFixed(2) : 'N/A' },
      { id: '3', label: 'Top Category', value: mostSpendCategory },
    ];
  }, [expenses, budget, spent]);

  const handleExport = async () => {
    setExporting(true);
    try {
      const token = await AsyncStorage.getItem('accessToken');
      const now = new Date();
      const year = now.getFullYear();
      const month = now.getMonth() + 1; // 1-indexed for backend

      const res = await ReactNativeBlobUtil.config({
        fileCache: true,
        appendExt: 'pdf',
      }).fetch(
        'GET',
        `${API_BASE_URL}/expense/v1/report/pdf?year=${year}&month=${month}`,
        { Authorization: `Bearer ${token}` }
      );

      const filePath = res.path();
      
      await Share.open({
        url: `file://${filePath}`,
        title: 'Export Monthly Report',
        type: 'application/pdf',
      });
      
    } catch (error) {
      console.error('Export error:', error);
    } finally {
      setExporting(false);
    }
  };

  return (
    <View style={styles.spendingStatusContainer}> 
      <View style={styles.headerRow}>
        <CustomText style={styles.title}>Monthly Insights</CustomText>
        <TouchableOpacity onPress={handleExport} disabled={exporting} style={styles.exportButton}>
          {exporting ? (
            <ActivityIndicator size="small" color={theme.colors.primary} />
          ) : (
            <>
              <Download size={14} color={theme.colors.primary} style={{ marginRight: 4 }} />
              <CustomText style={styles.exportText}>Export</CustomText>
            </>
          )}
        </TouchableOpacity>
      </View>

      {insightsData.map((insight) => (
        <View key={insight.id} style={styles.insightRow}>
          <CustomText style={styles.insightLabel}>• {insight.label}: </CustomText>
          <CustomText style={styles.insightValue}>{insight.value}</CustomText>
        </View>
      ))}  
    </View>
  );
};

export default SpendsInsights;

const styles = StyleSheet.create({
  spendingStatusContainer: {
    flexDirection: 'column',
    justifyContent: 'flex-start',
    alignItems: 'flex-start',
    backgroundColor: '#f8f9fa',
    padding: 15,
    borderRadius: 12,
    marginTop: 10,
    width: '100%',
  },
  headerRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    width: '100%',
    alignItems: 'center',
    marginBottom: 10,
  },
  title: {
    fontSize: 16,
    fontWeight: 'bold',
  },
  exportButton: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#e6f0ff',
    paddingHorizontal: 10,
    paddingVertical: 5,
    borderRadius: 8,
  },
  exportText: {
    color: theme.colors.primary,
    fontSize: 12,
    fontWeight: 'bold',
  },
  insightRow: {
    flexDirection: 'row',
    marginBottom: 6,
  },
  insightLabel: {
    fontWeight: '600',
    color: '#555',
    fontSize: 14,
  },
  insightValue: {
    fontWeight: 'bold',
    color: '#000',
    fontSize: 14,
  }
});