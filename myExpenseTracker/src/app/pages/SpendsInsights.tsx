import { StyleSheet, View, TouchableOpacity, ActivityIndicator } from 'react-native';
import React, { useMemo, useState } from 'react';
import { Text } from 'react-native';
import { ExpenseDto } from './dto/ExpenseDto';
import { Download, TrendingUp, Zap, Tag } from 'lucide-react-native';
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

  const insights = useMemo(() => {
    let mostSpendCategory = 'N/A';
    let highestTransaction = 0;

    if (expenses && expenses.length > 0) {
      const categoryTotals: Record<string, number> = {};
      expenses.forEach(exp => {
        const cat = exp.category || 'OTHER';
        categoryTotals[cat] = (categoryTotals[cat] || 0) + exp.amount;
        if (exp.amount > highestTransaction) highestTransaction = exp.amount;
      });
      let maxAmount = 0;
      Object.entries(categoryTotals).forEach(([cat, total]) => {
        if (total > maxAmount) { maxAmount = total; mostSpendCategory = cat; }
      });
    }

    let status = 'Good';
    let statusColor = '#22c55e';
    if (budget && budget > 0) {
      if (spent >= budget) { status = 'Over Budget'; statusColor = '#ef4444'; }
      else if (spent >= budget * 0.8) { status = 'At Risk'; statusColor = '#f59e0b'; }
    }

    const pct = budget && budget > 0 ? Math.min((spent / budget) * 100, 100) : 0;

    return { mostSpendCategory, highestTransaction, status, statusColor, pct };
  }, [expenses, budget, spent]);

  const handleExport = async () => {
    setExporting(true);
    try {
      const token = await AsyncStorage.getItem('accessToken');
      const now = new Date();
      const res = await ReactNativeBlobUtil.config({ fileCache: true, appendExt: 'pdf' }).fetch(
        'GET',
        `${API_BASE_URL}/expense/v1/report/pdf?year=${now.getFullYear()}&month=${now.getMonth() + 1}`,
        { Authorization: `Bearer ${token}` }
      );
      await Share.open({ url: `file://${res.path()}`, title: 'Export Monthly Report', type: 'application/pdf' });
    } catch (e) {
      console.error('Export error:', e);
    } finally {
      setExporting(false);
    }
  };

  return (
    <View style={styles.container}>
      <View style={styles.header}>
        <Text style={styles.sectionTitle}>Monthly Insights</Text>
        <TouchableOpacity onPress={handleExport} disabled={exporting} style={styles.exportBtn}>
          {exporting ? (
            <ActivityIndicator size="small" color="#818cf8" />
          ) : (
            <>
              <Download size={13} color="#818cf8" />
              <Text style={styles.exportText}> Export</Text>
            </>
          )}
        </TouchableOpacity>
      </View>

      <View style={styles.grid}>
        {/* Status Card */}
        <View style={[styles.card, styles.cardWide]}>
          <View style={[styles.cardIcon, { backgroundColor: '#0f172a' }]}>
            <Zap size={16} color={insights.statusColor} />
          </View>
          <Text style={styles.cardLabel}>Status</Text>
          <Text style={[styles.cardValue, { color: insights.statusColor }]}>{insights.status}</Text>
          {budget && budget > 0 ? (
            <View style={styles.progressBar}>
              <View style={[styles.progressFill, { width: `${insights.pct}%` as any, backgroundColor: insights.statusColor }]} />
            </View>
          ) : null}
          {budget && budget > 0 ? (
            <Text style={styles.budgetSubtext}>
              ₹{spent.toFixed(0)} / ₹{budget.toFixed(0)}
            </Text>
          ) : null}
        </View>

        {/* Highest Txn Card */}
        <View style={styles.card}>
          <View style={[styles.cardIcon, { backgroundColor: '#0f172a' }]}>
            <TrendingUp size={16} color="#818cf8" />
          </View>
          <Text style={styles.cardLabel}>Highest Txn</Text>
          <Text style={styles.cardValue}>
            {insights.highestTransaction > 0 ? `₹${insights.highestTransaction.toFixed(0)}` : '—'}
          </Text>
        </View>

        {/* Top Category Card */}
        <View style={styles.card}>
          <View style={[styles.cardIcon, { backgroundColor: '#0f172a' }]}>
            <Tag size={16} color="#f59e0b" />
          </View>
          <Text style={styles.cardLabel}>Top Category</Text>
          <Text style={[styles.cardValue, { fontSize: 14 }]} numberOfLines={1}>
            {insights.mostSpendCategory}
          </Text>
        </View>
      </View>
    </View>
  );
};

export default SpendsInsights;

const styles = StyleSheet.create({
  container: {
    marginHorizontal: 16,
    marginBottom: 8,
  },
  header: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 12,
  },
  sectionTitle: {
    fontSize: 16,
    fontWeight: '700',
    color: '#f1f5f9',
    letterSpacing: 0.2,
  },
  exportBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#1e1b4b',
    paddingHorizontal: 12,
    paddingVertical: 6,
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#4f46e5',
  },
  exportText: {
    color: '#818cf8',
    fontSize: 12,
    fontWeight: '600',
  },
  grid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 10,
  },
  card: {
    flex: 1,
    minWidth: '40%',
    backgroundColor: '#1e293b',
    borderRadius: 14,
    padding: 14,
    borderWidth: 1,
    borderColor: '#334155',
  },
  cardWide: {
    width: '100%',
    flex: 0,
  },
  cardIcon: {
    width: 32,
    height: 32,
    borderRadius: 8,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 8,
  },
  cardLabel: {
    fontSize: 11,
    fontWeight: '600',
    color: '#64748b',
    textTransform: 'uppercase',
    letterSpacing: 0.6,
    marginBottom: 4,
  },
  cardValue: {
    fontSize: 18,
    fontWeight: '800',
    color: '#f1f5f9',
  },
  progressBar: {
    height: 4,
    backgroundColor: '#334155',
    borderRadius: 2,
    marginTop: 10,
    overflow: 'hidden',
  },
  progressFill: {
    height: '100%',
    borderRadius: 2,
  },
  budgetSubtext: {
    fontSize: 11,
    color: '#64748b',
    marginTop: 4,
    fontWeight: '500',
  },
});