import React, { useState, useEffect } from 'react';
import {
  View,
  StyleSheet,
  Image,
  ScrollView,
  TouchableOpacity,
  Platform,
  Modal,
  TextInput,
  ActivityIndicator,
} from 'react-native';
import CustomText from '../components/CustomText';
import { theme } from '../theme/theme';
import { Icon } from '@gluestack-ui/themed';
import { Camera, ChevronRight, User, Phone, Mail, Bell, Lock, Moon, X, Check, Wallet } from 'lucide-react-native';
import UserService, { UserInfoDTO, UpdateProfileRequest } from '../api/UserService';
import SubscriptionService, { Subscription } from '../api/SubscriptionService';
import { Trash2, Plus } from 'lucide-react-native';

interface ProfileItemProps {
  icon: React.ReactNode;
  label: string;
  value: string;
}

const ProfileItem: React.FC<ProfileItemProps> = ({ icon, label, value }) => (
  <View style={styles.profileItem}>
    <View style={styles.iconContainer}>
      {icon}
    </View>
    <View style={styles.itemContent}>
      <CustomText style={styles.label}>{label}</CustomText>
      <CustomText style={styles.value}>{value || 'Not set'}</CustomText>
    </View>
    <Icon as={ChevronRight} color={theme.colors.text.secondary} size="sm" />
  </View>
);

const Profile = () => {
  const [user, setUser] = useState<UserInfoDTO | null>(null);
  const [loading, setLoading] = useState(true);
  
  const [isEditing, setIsEditing] = useState(false);
  const [editForm, setEditForm] = useState<UpdateProfileRequest>({});
  const [saving, setSaving] = useState(false);

  const [subscriptions, setSubscriptions] = useState<Subscription[]>([]);
  const [isSubModalVisible, setIsSubModalVisible] = useState(false);
  const [subForm, setSubForm] = useState<Partial<Subscription>>({ currency: 'INR' });
  const [subSaving, setSubSaving] = useState(false);

  const fetchProfile = async () => {
    setLoading(true);
    const [profile, subs] = await Promise.all([
      UserService.getUserProfile(),
      SubscriptionService.getSubscriptions()
    ]);
    setUser(profile);
    setSubscriptions(subs);
    setLoading(false);

    if (profile && (!profile.first_name || !profile.email)) {
      // Auto-trigger onboarding if missing essential info
      startEditing(profile);
    }
  };

  useEffect(() => {
    fetchProfile();
  }, []);

  const formatPhoneNumber = (phone: string | null): string => {
    if (!phone) return 'Not set';
    return phone;
  };

  const startEditing = (currentUser: UserInfoDTO | null = user) => {
    if (currentUser) {
      setEditForm({
        first_name: currentUser.first_name || '',
        last_name: currentUser.last_name || '',
        email: currentUser.email || '',
        monthly_budget: currentUser.monthly_budget || undefined,
      });
    }
    setIsEditing(true);
  };

  const handleSave = async () => {
    setSaving(true);
    const success = await UserService.updateUserProfile(editForm);
    setSaving(false);
    if (success) {
      setIsEditing(false);
      fetchProfile();
    } else {
      alert("Failed to save profile.");
    }
  };

  const handleSaveSub = async () => {
    if (!subForm.platform || !subForm.amount || !subForm.billingDay) return;
    setSubSaving(true);
    const success = await SubscriptionService.createSubscription(subForm as Subscription);
    setSubSaving(false);
    if (success) {
      setIsSubModalVisible(false);
      setSubForm({ currency: 'INR' });
      fetchProfile();
    } else {
      alert("Failed to save subscription.");
    }
  };

  const handleDeleteSub = async (id: number) => {
    const success = await SubscriptionService.deleteSubscription(id);
    if (success) fetchProfile();
  };

  if (loading) {
    return (
      <View style={[styles.container, styles.center]}>
        <ActivityIndicator size="large" color={theme.colors.primary} />
      </View>
    );
  }

  if (!user) {
    return (
      <View style={[styles.container, styles.center]}>
        <CustomText>Error loading profile</CustomText>
      </View>
    );
  }

  return (
    <ScrollView style={styles.container}>
      <View style={styles.header}>
        <View style={styles.profileImageContainer}>
          <Image
            source={{ uri: user.profile_picture || 'https://i.pravatar.cc/300' }}
            style={styles.profileImage}
          />
          <TouchableOpacity style={styles.editButton} onPress={() => startEditing()}>
            <Icon as={Camera} color={theme.colors.primary} size="sm" />
          </TouchableOpacity>
        </View>
        <CustomText style={styles.name}>
          {user.first_name ? `${user.first_name} ${user.last_name || ''}`.trim() : 'Welcome User!'}
        </CustomText>
        <View style={{ flexDirection: 'row', alignItems: 'center' }}>
          <CustomText style={styles.userId}>ID: {user.user_id}</CustomText>
          <View style={styles.streakBadge}>
            <CustomText style={styles.streakText}>🔥 {user.current_streak || 0} Day Streak</CustomText>
          </View>
        </View>
      </View>

      <View style={styles.content}>
        <View style={styles.section}>
          <View style={styles.sectionHeader}>
            <CustomText style={styles.sectionTitle}>Personal Information</CustomText>
            <TouchableOpacity onPress={() => startEditing()}>
              <CustomText style={styles.editLink}>Edit</CustomText>
            </TouchableOpacity>
          </View>
          <View style={styles.card}>
            <ProfileItem
              icon={<Icon as={User} color={theme.colors.primary} size="sm" />}
              label="Name"
              value={user.first_name ? `${user.first_name} ${user.last_name || ''}`.trim() : ''}
            />
            <ProfileItem
              icon={<Icon as={Phone} color={theme.colors.primary} size="sm" />}
              label="Phone"
              value={formatPhoneNumber(user.phone_number)}
            />
            <ProfileItem
              icon={<Icon as={Mail} color={theme.colors.primary} size="sm" />}
              label="Email"
              value={user.email || ''}
            />
            <ProfileItem
              icon={<Icon as={Wallet} color={theme.colors.primary} size="sm" />}
              label="Monthly Budget"
              value={user.monthly_budget ? `${user.default_currency || 'INR'} ${user.monthly_budget}` : 'Not set'}
            />
          </View>
        </View>

        <View style={styles.section}>
          <CustomText style={styles.sectionTitle}>Settings</CustomText>
          <View style={styles.card}>
            <ProfileItem
              icon={<Icon as={Bell} color={theme.colors.primary} size="sm" />}
              label="Notifications"
              value="On"
            />
            <ProfileItem
              icon={<Icon as={Lock} color={theme.colors.primary} size="sm" />}
              label="Privacy"
              value="View Settings"
            />
            <ProfileItem
              icon={<Icon as={Moon} color={theme.colors.primary} size="sm" />}
              label="Dark Mode"
              value="System"
            />
          </View>
        </View>

        <View style={styles.section}>
          <View style={styles.sectionHeader}>
            <CustomText style={styles.sectionTitle}>Subscriptions</CustomText>
            <TouchableOpacity onPress={() => setIsSubModalVisible(true)}>
              <Icon as={Plus} color={theme.colors.primary} size="sm" />
            </TouchableOpacity>
          </View>
          <View style={styles.card}>
            {subscriptions.length === 0 ? (
              <View style={styles.profileItem}>
                <CustomText style={styles.value}>No active subscriptions</CustomText>
              </View>
            ) : (
              subscriptions.map(sub => (
                <View key={sub.id} style={styles.profileItem}>
                  <View style={styles.itemContent}>
                    <CustomText style={styles.value}>{sub.platform}</CustomText>
                    <CustomText style={styles.label}>{sub.currency} {sub.amount} (Renews on {sub.billingDay})</CustomText>
                  </View>
                  <TouchableOpacity onPress={() => sub.id && handleDeleteSub(sub.id)}>
                    <Icon as={Trash2} color="#EF4444" size="sm" />
                  </TouchableOpacity>
                </View>
              ))
            )}
          </View>
        </View>
      </View>

      <Modal visible={isEditing} animationType="slide" transparent={true}>
        <View style={styles.modalOverlay}>
          <View style={styles.modalContent}>
            <View style={styles.modalHeader}>
              <TouchableOpacity onPress={() => setIsEditing(false)}>
                <Icon as={X} color={theme.colors.text.primary} size="md" />
              </TouchableOpacity>
              <CustomText style={styles.modalTitle}>Edit Profile</CustomText>
              <TouchableOpacity onPress={handleSave} disabled={saving}>
                {saving ? (
                  <ActivityIndicator size="small" color={theme.colors.primary} />
                ) : (
                  <Icon as={Check} color={theme.colors.primary} size="md" />
                )}
              </TouchableOpacity>
            </View>

            <ScrollView style={styles.formContainer}>
              <View style={styles.inputGroup}>
                <CustomText style={styles.inputLabel}>First Name</CustomText>
                <TextInput
                  style={styles.input}
                  value={editForm.first_name}
                  onChangeText={(text) => setEditForm(prev => ({...prev, first_name: text}))}
                  placeholder="Enter first name"
                  placeholderTextColor={theme.colors.text.secondary}
                />
              </View>
              <View style={styles.inputGroup}>
                <CustomText style={styles.inputLabel}>Last Name</CustomText>
                <TextInput
                  style={styles.input}
                  value={editForm.last_name}
                  onChangeText={(text) => setEditForm(prev => ({...prev, last_name: text}))}
                  placeholder="Enter last name"
                  placeholderTextColor={theme.colors.text.secondary}
                />
              </View>
              <View style={styles.inputGroup}>
                <CustomText style={styles.inputLabel}>Email</CustomText>
                <TextInput
                  style={styles.input}
                  value={editForm.email}
                  onChangeText={(text) => setEditForm(prev => ({...prev, email: text}))}
                  placeholder="Enter email"
                  keyboardType="email-address"
                  autoCapitalize="none"
                  placeholderTextColor={theme.colors.text.secondary}
                />
              </View>
              <View style={styles.inputGroup}>
                <CustomText style={styles.inputLabel}>Monthly Budget</CustomText>
                <TextInput
                  style={styles.input}
                  value={editForm.monthly_budget ? editForm.monthly_budget.toString() : ''}
                  onChangeText={(text) => setEditForm(prev => ({...prev, monthly_budget: parseFloat(text) || undefined}))}
                  placeholder="Enter monthly budget (e.g. 5000)"
                  keyboardType="numeric"
                  placeholderTextColor={theme.colors.text.secondary}
                />
              </View>
            </ScrollView>
          </View>
        </View>
      </Modal>

      <Modal visible={isSubModalVisible} animationType="slide" transparent={true}>
        <View style={styles.modalOverlay}>
          <View style={styles.modalContent}>
            <View style={styles.modalHeader}>
              <TouchableOpacity onPress={() => setIsSubModalVisible(false)}>
                <Icon as={X} color={theme.colors.text.primary} size="md" />
              </TouchableOpacity>
              <CustomText style={styles.modalTitle}>Add Subscription</CustomText>
              <TouchableOpacity onPress={handleSaveSub} disabled={subSaving || !subForm.platform || !subForm.amount || !subForm.billingDay}>
                {subSaving ? (
                  <ActivityIndicator size="small" color={theme.colors.primary} />
                ) : (
                  <Icon as={Check} color={theme.colors.primary} size="md" />
                )}
              </TouchableOpacity>
            </View>

            <ScrollView style={styles.formContainer}>
              <View style={styles.inputGroup}>
                <CustomText style={styles.inputLabel}>Platform Name</CustomText>
                <TextInput
                  style={styles.input}
                  value={subForm.platform}
                  onChangeText={(text) => setSubForm(prev => ({...prev, platform: text}))}
                  placeholder="e.g. Netflix, Spotify"
                  placeholderTextColor={theme.colors.text.secondary}
                />
              </View>
              <View style={styles.inputGroup}>
                <CustomText style={styles.inputLabel}>Amount</CustomText>
                <TextInput
                  style={styles.input}
                  value={subForm.amount ? subForm.amount.toString() : ''}
                  onChangeText={(text) => setSubForm(prev => ({...prev, amount: parseFloat(text)}))}
                  placeholder="e.g. 199"
                  keyboardType="numeric"
                  placeholderTextColor={theme.colors.text.secondary}
                />
              </View>
              <View style={styles.inputGroup}>
                <CustomText style={styles.inputLabel}>Billing Day (1-31)</CustomText>
                <TextInput
                  style={styles.input}
                  value={subForm.billingDay ? subForm.billingDay.toString() : ''}
                  onChangeText={(text) => setSubForm(prev => ({...prev, billingDay: parseInt(text)}))}
                  placeholder="e.g. 15"
                  keyboardType="numeric"
                  placeholderTextColor={theme.colors.text.secondary}
                />
              </View>
            </ScrollView>
          </View>
        </View>
      </Modal>
    </ScrollView>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: theme.colors.background.primary,
  },
  center: {
    justifyContent: 'center',
    alignItems: 'center',
  },
  header: {
    alignItems: 'center',
    paddingVertical: theme.spacing['2xl'],
    backgroundColor: theme.colors.surface.primary,
    borderBottomWidth: 1,
    borderBottomColor: theme.colors.effects.glass.borderColor,
  },
  profileImageContainer: {
    position: 'relative',
    marginBottom: theme.spacing.lg,
  },
  profileImage: {
    width: 120,
    height: 120,
    borderRadius: 60,
    borderWidth: 3,
    borderColor: theme.colors.primary,
  },
  editButton: {
    position: 'absolute',
    bottom: 0,
    right: 0,
    backgroundColor: theme.colors.surface.secondary,
    borderRadius: theme.borderRadius.full,
    padding: theme.spacing.sm,
    borderWidth: 2,
    borderColor: theme.colors.primary,
  },
  name: {
    fontSize: theme.typography.fontSize['2xl'],
    fontWeight: theme.typography.fontWeight.bold,
    color: theme.colors.text.primary,
    marginBottom: theme.spacing.xs,
  },
  userId: {
    fontSize: theme.typography.fontSize.sm,
    color: theme.colors.text.secondary,
  },
  streakBadge: {
    backgroundColor: '#FFF4E5',
    paddingHorizontal: 8,
    paddingVertical: 2,
    borderRadius: 12,
    marginLeft: 8,
    borderWidth: 1,
    borderColor: '#FFB020',
  },
  streakText: {
    fontSize: 12,
    color: '#D14300',
    fontWeight: 'bold',
  },
  content: {
    padding: theme.spacing.lg,
  },
  section: {
    marginBottom: theme.spacing.xl,
  },
  sectionHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: theme.spacing.md,
    paddingHorizontal: theme.spacing.sm,
  },
  sectionTitle: {
    fontSize: theme.typography.fontSize.lg,
    fontWeight: theme.typography.fontWeight.semibold,
    color: theme.colors.text.primary,
  },
  editLink: {
    fontSize: theme.typography.fontSize.sm,
    color: theme.colors.primary,
    fontWeight: theme.typography.fontWeight.medium,
  },
  card: {
    backgroundColor: theme.colors.surface.primary,
    borderRadius: theme.borderRadius.lg,
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
  profileItem: {
    flexDirection: 'row',
    alignItems: 'center',
    padding: theme.spacing.lg,
    borderBottomWidth: 1,
    borderBottomColor: theme.colors.effects.glass.borderColor,
  },
  iconContainer: {
    width: 32,
    height: 32,
    borderRadius: theme.borderRadius.full,
    backgroundColor: theme.colors.surface.elevated,
    alignItems: 'center',
    justifyContent: 'center',
    marginRight: theme.spacing.md,
  },
  itemContent: {
    flex: 1,
  },
  label: {
    fontSize: theme.typography.fontSize.sm,
    color: theme.colors.text.secondary,
    marginBottom: 2,
  },
  value: {
    fontSize: theme.typography.fontSize.base,
    color: theme.colors.text.primary,
    fontWeight: theme.typography.fontWeight.medium,
  },
  modalOverlay: {
    flex: 1,
    backgroundColor: 'rgba(0,0,0,0.5)',
    justifyContent: 'flex-end',
  },
  modalContent: {
    backgroundColor: theme.colors.surface.primary,
    borderTopLeftRadius: theme.borderRadius.xl,
    borderTopRightRadius: theme.borderRadius.xl,
    height: '80%',
  },
  modalHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    padding: theme.spacing.lg,
    borderBottomWidth: 1,
    borderBottomColor: theme.colors.effects.glass.borderColor,
  },
  modalTitle: {
    fontSize: theme.typography.fontSize.lg,
    fontWeight: theme.typography.fontWeight.bold,
    color: theme.colors.text.primary,
  },
  formContainer: {
    padding: theme.spacing.lg,
  },
  inputGroup: {
    marginBottom: theme.spacing.lg,
  },
  inputLabel: {
    fontSize: theme.typography.fontSize.sm,
    color: theme.colors.text.secondary,
    marginBottom: theme.spacing.xs,
    marginLeft: theme.spacing.xs,
  },
  input: {
    backgroundColor: theme.colors.surface.secondary,
    borderWidth: 1,
    borderColor: theme.colors.effects.glass.borderColor,
    borderRadius: theme.borderRadius.md,
    padding: theme.spacing.md,
    fontSize: theme.typography.fontSize.base,
    color: theme.colors.text.primary,
  },
});

export default Profile;