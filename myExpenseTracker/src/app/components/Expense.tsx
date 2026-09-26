import {StyleSheet, View, Image, Platform, TouchableOpacity, Modal, TextInput, ActivityIndicator, Alert} from 'react-native';
import React, { useState } from 'react';
import CustomText from './CustomText';
import { ExpenseDto } from '../pages/dto/ExpenseDto';
import { theme } from '../theme/theme';
import { Edit2 } from 'lucide-react-native';
import ExpenseService from '../api/ExpenseService';

interface ExpenseProps {
  props: ExpenseDto;
  onRefresh?: () => void;
}

const Expense: React.FC<ExpenseProps> = ({props, onRefresh}) => {
  const [isRenameVisible, setIsRenameVisible] = useState(false);
  const [aliasName, setAliasName] = useState('');
  const [isSaving, setIsSaving] = useState(false);

  const formatDate = (date: Date) => {
    return new Date(date).toLocaleDateString('en-US', {
      month: 'short',
      day: 'numeric',
    });
  };

  const handleRenameSubmit = async () => {
    if (!aliasName.trim()) return;
    setIsSaving(true);
    const success = await ExpenseService.createMerchantAlias(props.merchant, aliasName.trim());
    setIsSaving(false);
    
    if (success) {
      setIsRenameVisible(false);
      setAliasName('');
      if (onRefresh) onRefresh();
    } else {
      Alert.alert("Failed to rename merchant.");
    }
  };

  return (
    <>
      <View style={styles.expenseContainer} key={props.key}>
        <View style={styles.leftContent}>
          <View style={styles.imageContainer}>
            <Image
              source={{uri: 'https://media.istockphoto.com/id/1206806317/vector/shopping-cart-icon-isolated-on-white-background.jpg?s=612x612&w=0&k=20&c=1RRQJs5NDhcB67necQn1WCpJX2YMfWZ4rYi1DFKlkNA='}}
              style={styles.expenseImage}
            />
          </View>
          <View style={styles.merchantInfo}>
            <View style={{ flexDirection: 'row', alignItems: 'center' }}>
              <CustomText style={styles.merchantText}>{props.merchant}</CustomText>
              <TouchableOpacity onPress={() => setIsRenameVisible(true)} style={{ marginLeft: 6, marginBottom: 4 }}>
                <Edit2 color="#999" size={14} />
              </TouchableOpacity>
            </View>
            <CustomText style={styles.dateText}>{formatDate(props.createdAt)}</CustomText>
          </View>
        </View>
        <View style={styles.rightContent}>
          <CustomText style={styles.amountText}>
            {props.currency} {props.amount.toFixed(2)}
          </CustomText>
        </View>
      </View>

      <Modal
        visible={isRenameVisible}
        animationType="slide"
        transparent={true}
        onRequestClose={() => setIsRenameVisible(false)}
      >
        <View style={styles.modalOverlay}>
          <View style={styles.modalContent}>
            <CustomText style={styles.modalTitle}>Rename Merchant</CustomText>
            <CustomText style={styles.modalSubtitle}>
              Rename "{props.merchant}". All past and future transactions will use the new name.
            </CustomText>
            
            <TextInput
              style={styles.textInput}
              placeholder="Enter new name..."
              value={aliasName}
              onChangeText={setAliasName}
              autoFocus
            />

            <View style={styles.modalActions}>
              <TouchableOpacity 
                style={[styles.modalButton, styles.cancelButton]} 
                onPress={() => setIsRenameVisible(false)}
                disabled={isSaving}
              >
                <CustomText style={styles.cancelButtonText}>Cancel</CustomText>
              </TouchableOpacity>
              
              <TouchableOpacity 
                style={[styles.modalButton, styles.saveButton]} 
                onPress={handleRenameSubmit}
                disabled={isSaving || !aliasName.trim()}
              >
                {isSaving ? (
                  <ActivityIndicator size="small" color="#fff" />
                ) : (
                  <CustomText style={styles.saveButtonText}>Save</CustomText>
                )}
              </TouchableOpacity>
            </View>
          </View>
        </View>
      </Modal>
    </>
  );
};

const styles = StyleSheet.create({
  expenseContainer: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    backgroundColor: theme.colors.surface.primary,
    borderRadius: theme.borderRadius.lg,
    padding: theme.spacing.lg,
    marginVertical: theme.spacing.xs,
    ...Platform.select({
      ios: {
        shadowColor: theme.colors.effects.shadow.medium.color,
        shadowOffset: theme.colors.effects.shadow.medium.offset,
        shadowOpacity: theme.colors.effects.shadow.medium.opacity,
        shadowRadius: theme.colors.effects.shadow.medium.radius,
      },
      android: {
        elevation: 4,
      },
    }),
    borderWidth: 1,
    borderColor: theme.colors.effects.glass.borderColor,
  },
  leftContent: {
    flexDirection: 'row',
    alignItems: 'center',
    flex: 1,
  },
  imageContainer: {
    backgroundColor: theme.colors.surface.elevated,
    borderRadius: theme.borderRadius.md,
    padding: theme.spacing.xs,
    ...Platform.select({
      ios: {
        shadowColor: theme.colors.effects.shadow.light.color,
        shadowOffset: theme.colors.effects.shadow.light.offset,
        shadowOpacity: theme.colors.effects.shadow.light.opacity,
        shadowRadius: theme.colors.effects.shadow.light.radius,
      },
      android: {
        elevation: 2,
      },
    }),
  },
  rightContent: {
    alignItems: 'flex-end',
    justifyContent: 'center',
  },
  expenseImage: {
    width: 40,
    height: 40,
    borderRadius: theme.borderRadius.md,
  },
  merchantInfo: {
    flex: 1,
    marginLeft: theme.spacing.md,
  },
  merchantText: {
    color: theme.colors.text.primary,
    fontSize: theme.typography.fontSize.base,
    fontWeight: theme.typography.fontWeight.semibold,
    marginBottom: 4,
  },
  dateText: {
    color: theme.colors.text.secondary,
    fontSize: theme.typography.fontSize.sm,
    lineHeight: theme.typography.lineHeight.tight,
  },
  amountText: {
    color: theme.colors.primary,
    fontSize: theme.typography.fontSize.lg,
    fontWeight: theme.typography.fontWeight.bold,
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
    minHeight: 250,
    borderTopWidth: 1,
    borderColor: '#334155',
  },
  modalTitle: {
    fontSize: 20,
    fontWeight: '700',
    color: '#f1f5f9',
    marginBottom: 8,
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
    marginBottom: 24,
    backgroundColor: '#0f172a',
    color: '#f1f5f9',
  },
  modalActions: {
    flexDirection: 'row',
    gap: 12,
  },
  modalButton: {
    flex: 1,
    paddingVertical: 14,
    borderRadius: 12,
    alignItems: 'center',
  },
  cancelButton: {
    backgroundColor: '#334155',
  },
  saveButton: {
    backgroundColor: '#4f46e5',
  },
  cancelButtonText: {
    color: '#94a3b8',
    fontWeight: '600',
    fontSize: 15,
  },
  saveButtonText: {
    color: '#fff',
    fontWeight: '700',
    fontSize: 15,
  },
});

export default Expense;