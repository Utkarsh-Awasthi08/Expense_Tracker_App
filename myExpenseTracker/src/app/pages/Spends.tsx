import React, { useState } from 'react';
import {
  View,
  StyleSheet,
  TouchableOpacity,
  ActivityIndicator,
  Modal,
  TextInput,
  ScrollView,
  Alert,
  Text,
} from 'react-native';
import Expense from '../components/Expense';
import { ExpenseDto } from '../pages/dto/ExpenseDto';
import SmsService from '../api/SmsService';
import ManualExpenseService from '../api/ManualExpenseService';
import { Plus, RefreshCw, X } from 'lucide-react-native';
import CircularProgress from 'react-native-circular-progress-indicator';

interface SpendsProps {
  expenses: ExpenseDto[];
  isLoading: boolean;
  budget: number | null;
  spent: number;
  filterType: '7days' | 'month' | 'all';
  setFilterType: (type: '7days' | 'month' | 'all') => void;
  sortOrder: 'date' | 'price';
  setSortOrder: (order: 'date' | 'price') => void;
  onRefresh: () => void;
}

const FilterPill = ({
  label,
  isActive,
  onPress,
}: {
  label: string;
  isActive: boolean;
  onPress: () => void;
}) => (
  <TouchableOpacity
    style={[styles.filterPill, isActive && styles.filterPillActive]}
    onPress={onPress}
    activeOpacity={0.7}
  >
    <Text style={[styles.filterPillText, isActive && styles.filterPillTextActive]}>
      {label}
    </Text>
  </TouchableOpacity>
);

const Spends: React.FC<SpendsProps> = ({
  expenses,
  isLoading,
  budget,
  spent,
  filterType,
  setFilterType,
  sortOrder,
  setSortOrder,
  onRefresh,
}) => {
  const [isSyncing, setIsSyncing] = useState(false);
  const [isManualModalVisible, setManualModalVisible] = useState(false);
  const [manualText, setManualText] = useState('');
  const [isManualSaving, setIsManualSaving] = useState(false);

  const handleSyncSms = async () => {
    setIsSyncing(true);
    const success = await SmsService.syncSmsMessages();
    setIsSyncing(false);
    if (success) onRefresh();
  };

  const handleManualSubmit = async () => {
    if (!manualText.trim()) return;
    setIsManualSaving(true);
    const success = await ManualExpenseService.submitExpense(manualText);
    setIsManualSaving(false);
    if (success) {
      setManualText('');
      setManualModalVisible(false);
      setTimeout(() => onRefresh(), 1500);
    } else {
      Alert.alert('Error', 'Failed to save expense. Please try again.');
    }
  };

  return (
    <View style={styles.container}>
      {/* Section Header */}
      <View style={styles.sectionHeader}>
        <View>
          <Text style={styles.sectionTitle}>Recent Spends</Text>
          <Text style={styles.sectionSubtitle}>{expenses.length} transactions</Text>
        </View>
        <TouchableOpacity
          style={styles.syncBtn}
          onPress={handleSyncSms}
          disabled={isSyncing || isLoading}
          activeOpacity={0.8}
        >
          {isSyncing ? (
            <ActivityIndicator size="small" color="#818cf8" />
          ) : (
            <>
              <RefreshCw size={14} color="#818cf8" />
              <Text style={styles.syncBtnText}> Sync SMS</Text>
            </>
          )}
        </TouchableOpacity>
      </View>

      {/* Filter Bar */}
      <ScrollView
        horizontal
        showsHorizontalScrollIndicator={false}
        contentContainerStyle={styles.filterScroll}
        style={styles.filterContainer}
      >
        <FilterPill label="7 Days" isActive={filterType === '7days'} onPress={() => setFilterType('7days')} />
        <FilterPill label="This Month" isActive={filterType === 'month'} onPress={() => setFilterType('month')} />
        <FilterPill label="All Time" isActive={filterType === 'all'} onPress={() => setFilterType('all')} />
        <View style={styles.filterDivider} />
        <FilterPill label="Latest First" isActive={sortOrder === 'date'} onPress={() => setSortOrder('date')} />
        <FilterPill label="Highest First" isActive={sortOrder === 'price'} onPress={() => setSortOrder('price')} />
      </ScrollView>

      {/* Budget Ring */}
      {budget && budget > 0 && filterType === 'month' && (
        <View style={styles.budgetCard}>
          <CircularProgress
            value={spent}
            radius={52}
            duration={900}
            progressValueColor={'#f1f5f9'}
            progressValueStyle={{ fontWeight: '800', fontSize: 14 }}
            maxValue={budget}
            title={'Spent'}
            titleColor={'#64748b'}
            titleStyle={{ fontWeight: '600', fontSize: 11 }}
            activeStrokeColor={
              spent >= budget ? '#ef4444' : spent >= budget * 0.8 ? '#f59e0b' : '#22c55e'
            }
            inActiveStrokeColor={'#1e293b'}
            activeStrokeWidth={10}
            inActiveStrokeWidth={10}
          />
          <View style={styles.budgetInfo}>
            <Text style={styles.budgetLabel}>Monthly Budget</Text>
            <Text style={styles.budgetTotal}>₹{budget.toLocaleString('en-IN')}</Text>
            <Text
              style={[
                styles.budgetRemaining,
                { color: budget - spent >= 0 ? '#22c55e' : '#ef4444' },
              ]}
            >
              {budget - spent >= 0
                ? `₹${(budget - spent).toFixed(0)} remaining`
                : `₹${(spent - budget).toFixed(0)} over budget`}
            </Text>
          </View>
        </View>
      )}

      {/* Expense List */}
      {isLoading && expenses.length === 0 ? (
        <View style={styles.emptyState}>
          <ActivityIndicator size="large" color="#4f46e5" />
          <Text style={styles.emptyText}>Loading expenses...</Text>
        </View>
      ) : expenses.length === 0 ? (
        <View style={styles.emptyState}>
          <Text style={styles.emptyEmoji}>🧾</Text>
          <Text style={styles.emptyTitle}>No expenses yet</Text>
          <Text style={styles.emptyText}>
            Tap + to add one manually, or sync your SMS messages.
          </Text>
        </View>
      ) : (
        <View style={styles.expenseList}>
          {expenses.map(expense => (
            <Expense key={expense.key} props={expense} onRefresh={onRefresh} />
          ))}
          <View style={{ height: 100 }} />
        </View>
      )}

      {/* FAB */}
      <TouchableOpacity style={styles.fab} onPress={() => setManualModalVisible(true)} activeOpacity={0.85}>
        <Plus color="#fff" size={24} />
      </TouchableOpacity>

      {/* Add Expense Modal */}
      <Modal
        visible={isManualModalVisible}
        animationType="slide"
        transparent
        onRequestClose={() => setManualModalVisible(false)}
      >
        <View style={styles.modalOverlay}>
          <View style={styles.modalContent}>
            <View style={styles.modalHeader}>
              <Text style={styles.modalTitle}>Add Expense</Text>
              <TouchableOpacity onPress={() => setManualModalVisible(false)}>
                <X size={22} color="#94a3b8" />
              </TouchableOpacity>
            </View>
            <Text style={styles.modalSubtitle}>
              Describe your expense naturally, e.g. "Spent ₹150 on coffee at Starbucks"
            </Text>
            <TextInput
              style={styles.textInput}
              placeholder="Type your expense..."
              placeholderTextColor="#475569"
              value={manualText}
              onChangeText={setManualText}
              multiline
              autoFocus
            />
            <View style={styles.modalActions}>
              <TouchableOpacity
                style={styles.cancelBtn}
                onPress={() => setManualModalVisible(false)}
                disabled={isManualSaving}
              >
                <Text style={styles.cancelBtnText}>Cancel</Text>
              </TouchableOpacity>
              <TouchableOpacity
                style={[styles.saveBtn, (!manualText.trim() || isManualSaving) && { opacity: 0.5 }]}
                onPress={handleManualSubmit}
                disabled={isManualSaving || !manualText.trim()}
              >
                {isManualSaving ? (
                  <ActivityIndicator size="small" color="#fff" />
                ) : (
                  <Text style={styles.saveBtnText}>Save</Text>
                )}
              </TouchableOpacity>
            </View>
          </View>
        </View>
      </Modal>
    </View>
  );
};

export default Spends;

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#0f172a',
  },
  sectionHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: 20,
    paddingTop: 20,
    paddingBottom: 12,
  },
  sectionTitle: {
    fontSize: 18,
    fontWeight: '700',
    color: '#f1f5f9',
    letterSpacing: 0.2,
  },
  sectionSubtitle: {
    fontSize: 12,
    color: '#475569',
    marginTop: 2,
    fontWeight: '500',
  },
  syncBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#1e1b4b',
    paddingHorizontal: 12,
    paddingVertical: 8,
    borderRadius: 10,
    borderWidth: 1,
    borderColor: '#4f46e5',
  },
  syncBtnText: {
    color: '#818cf8',
    fontWeight: '600',
    fontSize: 13,
  },
  filterContainer: {
    marginBottom: 4,
  },
  filterScroll: {
    paddingHorizontal: 16,
    alignItems: 'center',
  },
  filterPill: {
    backgroundColor: '#1e293b',
    paddingHorizontal: 14,
    paddingVertical: 7,
    borderRadius: 20,
    marginRight: 8,
    borderWidth: 1,
    borderColor: '#334155',
  },
  filterPillActive: {
    backgroundColor: '#4f46e5',
    borderColor: '#4f46e5',
  },
  filterPillText: {
    color: '#64748b',
    fontWeight: '600',
    fontSize: 13,
  },
  filterPillTextActive: {
    color: '#fff',
  },
  filterDivider: {
    width: 1,
    height: 18,
    backgroundColor: '#334155',
    marginHorizontal: 8,
  },
  budgetCard: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#1e293b',
    marginHorizontal: 16,
    marginTop: 16,
    borderRadius: 16,
    padding: 20,
    borderWidth: 1,
    borderColor: '#334155',
  },
  budgetInfo: {
    marginLeft: 20,
    flex: 1,
  },
  budgetLabel: {
    fontSize: 12,
    color: '#64748b',
    fontWeight: '600',
    textTransform: 'uppercase',
    letterSpacing: 0.6,
    marginBottom: 4,
  },
  budgetTotal: {
    fontSize: 22,
    fontWeight: '800',
    color: '#f1f5f9',
    marginBottom: 4,
  },
  budgetRemaining: {
    fontSize: 13,
    fontWeight: '600',
  },
  emptyState: {
    alignItems: 'center',
    paddingVertical: 60,
    paddingHorizontal: 40,
  },
  emptyEmoji: {
    fontSize: 48,
    marginBottom: 16,
  },
  emptyTitle: {
    fontSize: 18,
    fontWeight: '700',
    color: '#f1f5f9',
    marginBottom: 8,
  },
  emptyText: {
    fontSize: 14,
    color: '#64748b',
    textAlign: 'center',
    lineHeight: 20,
  },
  expenseList: {
    paddingHorizontal: 16,
    paddingTop: 12,
  },
  fab: {
    position: 'absolute',
    bottom: 32,
    right: 20,
    backgroundColor: '#4f46e5',
    width: 58,
    height: 58,
    borderRadius: 29,
    justifyContent: 'center',
    alignItems: 'center',
    shadowColor: '#4f46e5',
    shadowOpacity: 0.5,
    shadowOffset: { width: 0, height: 6 },
    shadowRadius: 12,
    elevation: 10,
  },
  modalOverlay: {
    flex: 1,
    backgroundColor: 'rgba(0,0,0,0.65)',
    justifyContent: 'flex-end',
  },
  modalContent: {
    backgroundColor: '#1e293b',
    borderTopLeftRadius: 24,
    borderTopRightRadius: 24,
    padding: 24,
    minHeight: 320,
    borderTopWidth: 1,
    borderColor: '#334155',
  },
  modalHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 8,
  },
  modalTitle: {
    fontSize: 20,
    fontWeight: '700',
    color: '#f1f5f9',
  },
  modalSubtitle: {
    fontSize: 13,
    color: '#64748b',
    marginBottom: 20,
    lineHeight: 18,
  },
  textInput: {
    borderWidth: 1,
    borderColor: '#334155',
    borderRadius: 12,
    padding: 16,
    fontSize: 16,
    minHeight: 100,
    textAlignVertical: 'top',
    marginBottom: 20,
    backgroundColor: '#0f172a',
    color: '#f1f5f9',
  },
  modalActions: {
    flexDirection: 'row',
    gap: 12,
  },
  cancelBtn: {
    flex: 1,
    paddingVertical: 14,
    borderRadius: 12,
    alignItems: 'center',
    backgroundColor: '#334155',
  },
  cancelBtnText: {
    color: '#94a3b8',
    fontWeight: '600',
    fontSize: 15,
  },
  saveBtn: {
    flex: 1,
    paddingVertical: 14,
    borderRadius: 12,
    alignItems: 'center',
    backgroundColor: '#4f46e5',
  },
  saveBtnText: {
    color: '#fff',
    fontWeight: '700',
    fontSize: 15,
  },
});