import React, { useState } from 'react';
import { View, StyleSheet, TouchableOpacity, ActivityIndicator, Modal, TextInput, ScrollView } from 'react-native';
import Heading from '../components/Heading';
import Expense from '../components/Expense';
import CustomBox from '../components/CustomBox';
import CustomText from '../components/CustomText';
import { ExpenseDto } from '../pages/dto/ExpenseDto';
import SmsService from '../api/SmsService';
import ManualExpenseService from '../api/ManualExpenseService';
import { Plus, Wallet, Bell, Lock, Moon, X, Check } from 'lucide-react-native';
import CircularProgress from 'react-native-circular-progress-indicator';
import { Icon } from '@gluestack-ui/themed';

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

const FilterPill = ({ label, isActive, onPress }: { label: string, isActive: boolean, onPress: () => void }) => (
  <TouchableOpacity 
    style={[styles.filterPill, isActive && styles.filterPillActive]} 
    onPress={onPress}
  >
    <CustomText style={[styles.filterPillText, isActive && styles.filterPillTextActive]}>{label}</CustomText>
  </TouchableOpacity>
);

const Spends: React.FC<SpendsProps> = ({ 
  expenses, isLoading, budget, spent, filterType, setFilterType, sortOrder, setSortOrder, onRefresh 
}) => {
  const [isSyncing, setIsSyncing] = useState<boolean>(false);
  
  // Manual Expense State
  const [isManualModalVisible, setManualModalVisible] = useState<boolean>(false);
  const [manualText, setManualText] = useState<string>('');
  const [isManualSaving, setIsManualSaving] = useState<boolean>(false);

  const handleSyncSms = async () => {
    setIsSyncing(true);
    const success = await SmsService.syncSmsMessages();
    setIsSyncing(false);
    if (success) {
      onRefresh();
    } else {
      console.log('SMS sync finished with errors or was denied.');
    }
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
      alert("Failed to save expense. Please try again.");
    }
  };

  if (isLoading && expenses.length === 0) {
    return (
      <View>
        <Heading props={{ heading: 'spends' }} />
        <CustomBox style={headingBox}>
          <CustomText style={{}}>Loading expenses...</CustomText>
        </CustomBox>
      </View>
    );
  }

  return (
    <View style={styles.container}>
      <View style={styles.headerRow}>
        <Heading props={{ heading: 'spends' }} />
        <TouchableOpacity style={styles.syncButton} onPress={handleSyncSms} disabled={isSyncing || isLoading}>
          {isSyncing ? (
            <ActivityIndicator size="small" color="#fff" />
          ) : (
            <CustomText style={styles.syncButtonText}>Sync SMS</CustomText>
          )}
        </TouchableOpacity>
      </View>
      
      {/* Filter Bar */}
      <View style={styles.filterContainer}>
        <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.filterScroll}>
          <FilterPill label="Last 7 Days" isActive={filterType === '7days'} onPress={() => setFilterType('7days')} />
          <FilterPill label="This Month" isActive={filterType === 'month'} onPress={() => setFilterType('month')} />
          <FilterPill label="All Time" isActive={filterType === 'all'} onPress={() => setFilterType('all')} />
          <View style={styles.filterDivider} />
          <FilterPill label="Date Desc" isActive={sortOrder === 'date'} onPress={() => setSortOrder('date')} />
          <FilterPill label="Price High-Low" isActive={sortOrder === 'price'} onPress={() => setSortOrder('price')} />
        </ScrollView>
      </View>
      
      {budget && budget > 0 && filterType === 'month' && (
        <View style={styles.budgetWidgetContainer}>
          <CircularProgress
            value={spent}
            radius={60}
            duration={1000}
            progressValueColor={'#000'}
            maxValue={budget}
            title={'Spent'}
            titleColor={'black'}
            titleStyle={{fontWeight: 'bold'}}
            activeStrokeColor={spent >= budget ? '#FF3B30' : spent >= budget * 0.8 ? '#FF9500' : '#34C759'}
            inActiveStrokeColor={'#e5e5e5'}
            activeStrokeWidth={12}
            inActiveStrokeWidth={12}
          />
          <View style={styles.budgetWidgetText}>
            <CustomText style={styles.budgetTitle}>Monthly Budget</CustomText>
            <CustomText style={styles.budgetRemainingText}>
              {budget - spent >= 0 ? `${(budget - spent).toFixed(2)} remaining` : `${(spent - budget).toFixed(2)} over budget`}
            </CustomText>
          </View>
        </View>
      )}

      {expenses.length === 0 ? (
        <CustomBox style={headingBox}>
          <CustomText style={{}}>No expenses found for this filter.</CustomText>
        </CustomBox>
      ) : (
        <CustomBox style={headingBox}>
          <View style={styles.expenses}>
            {expenses.map(expense => (
              <Expense key={expense.key} props={expense} onRefresh={onRefresh} />
            ))}
          </View>
        </CustomBox>
      )}

      {/* FAB for manual expense */}
      <TouchableOpacity 
        style={styles.fab} 
        onPress={() => setManualModalVisible(true)}
      >
        <Plus color="#fff" size={24} />
      </TouchableOpacity>

      {/* Modal for manual entry */}
      <Modal
        visible={isManualModalVisible}
        animationType="slide"
        transparent={true}
        onRequestClose={() => setManualModalVisible(false)}
      >
        <View style={styles.modalOverlay}>
          <View style={styles.modalContent}>
            <CustomText style={styles.modalTitle}>Add Expense</CustomText>
            <CustomText style={styles.modalSubtitle}>What did you spend on? (e.g. 'Spent $15 on coffee at Starbucks')</CustomText>
            
            <TextInput
              style={styles.textInput}
              placeholder="Type your expense..."
              value={manualText}
              onChangeText={setManualText}
              multiline
              autoFocus
            />

            <View style={styles.modalActions}>
              <TouchableOpacity 
                style={[styles.modalButton, styles.cancelButton]} 
                onPress={() => setManualModalVisible(false)}
                disabled={isManualSaving}
              >
                <CustomText style={styles.cancelButtonText}>Cancel</CustomText>
              </TouchableOpacity>
              
              <TouchableOpacity 
                style={[styles.modalButton, styles.saveButton]} 
                onPress={handleManualSubmit}
                disabled={isManualSaving || !manualText.trim()}
              >
                {isManualSaving ? (
                  <ActivityIndicator size="small" color="#fff" />
                ) : (
                  <CustomText style={styles.saveButtonText}>Save</CustomText>
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
  },
  headerRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingRight: 20,
  },
  syncButton: {
    backgroundColor: '#007BFF',
    paddingHorizontal: 16,
    paddingVertical: 10,
    borderRadius: 8,
    marginTop: 20,
  },
  syncButtonText: {
    color: '#fff',
    fontWeight: 'bold',
  },
  filterContainer: {
    marginTop: 15,
    marginBottom: 5,
  },
  filterScroll: {
    paddingHorizontal: 10,
    alignItems: 'center',
  },
  filterPill: {
    backgroundColor: '#F0F0F0',
    paddingHorizontal: 16,
    paddingVertical: 8,
    borderRadius: 20,
    marginRight: 8,
  },
  filterPillActive: {
    backgroundColor: '#007BFF',
  },
  filterPillText: {
    color: '#666',
    fontWeight: '600',
    fontSize: 14,
  },
  filterPillTextActive: {
    color: '#FFF',
  },
  filterDivider: {
    width: 1,
    height: 20,
    backgroundColor: '#CCC',
    marginHorizontal: 8,
  },
  expenses: {
    marginTop: 20,
  },
  fab: {
    position: 'absolute',
    bottom: 30,
    right: 20,
    backgroundColor: '#007BFF',
    width: 60,
    height: 60,
    borderRadius: 30,
    justifyContent: 'center',
    alignItems: 'center',
    elevation: 8,
    shadowColor: '#000',
    shadowOpacity: 0.3,
    shadowOffset: { width: 0, height: 4 },
    shadowRadius: 5,
  },
  budgetWidgetContainer: {
    flexDirection: 'row',
    alignItems: 'center',
    padding: 20,
    backgroundColor: '#fff',
    marginHorizontal: 10,
    borderRadius: 16,
    marginTop: 10,
    shadowColor: '#000',
    shadowOpacity: 0.1,
    shadowOffset: { width: 0, height: 2 },
    shadowRadius: 8,
    elevation: 3,
  },
  budgetWidgetText: {
    marginLeft: 20,
    flex: 1,
  },
  budgetTitle: {
    fontSize: 18,
    fontWeight: 'bold',
    marginBottom: 4,
  },
  budgetRemainingText: {
    fontSize: 14,
    color: '#666',
  },
  modalOverlay: {
    flex: 1,
    backgroundColor: 'rgba(0,0,0,0.5)',
    justifyContent: 'flex-end',
  },
  modalContent: {
    backgroundColor: '#fff',
    borderTopLeftRadius: 20,
    borderTopRightRadius: 20,
    padding: 24,
    minHeight: 300,
  },
  modalTitle: {
    fontSize: 22,
    fontWeight: 'bold',
    marginBottom: 8,
  },
  modalSubtitle: {
    fontSize: 14,
    color: '#666',
    marginBottom: 20,
  },
  textInput: {
    borderWidth: 1,
    borderColor: '#E0E0E0',
    borderRadius: 12,
    padding: 16,
    fontSize: 16,
    minHeight: 100,
    textAlignVertical: 'top',
    marginBottom: 24,
    backgroundColor: '#F9F9F9',
  },
  modalActions: {
    flexDirection: 'row',
    justifyContent: 'space-between',
  },
  modalButton: {
    flex: 1,
    paddingVertical: 14,
    borderRadius: 12,
    alignItems: 'center',
  },
  cancelButton: {
    backgroundColor: '#F0F0F0',
    marginRight: 10,
  },
  saveButton: {
    backgroundColor: '#007BFF',
    marginLeft: 10,
  },
  cancelButtonText: {
    color: '#333',
    fontWeight: 'bold',
    fontSize: 16,
  },
  saveButtonText: {
    color: '#fff',
    fontWeight: 'bold',
    fontSize: 16,
  },
});

const headingBox = {
  mainBox: {
    backgroundColor: 'white',
    borderColor: 'black',
  },
  shadowBox: {
    backgroundColor: 'gray',
  },
  styles: {
    marginTop: 20,
    paddingBottom: 80, // Add padding to not be hidden by FAB
  },
};