import { NativeModules, PermissionsAndroid, Platform } from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { API_BASE_URL } from './config';

const { SmsModule } = NativeModules;

export interface SmsMessage {
  id: string;
  address: string;
  body: string;
  date: string;
}

class SmsService {
  /**
   * Request permissions and sync latest SMS messages to the backend
   * @param limit Number of recent messages to fetch
   */
  async syncSmsMessages(limit: number = 50): Promise<boolean> {
    if (Platform.OS !== 'android') {
      console.log('SMS sync is only supported on Android');
      return false;
    }

    try {
      const granted = await PermissionsAndroid.request(
        PermissionsAndroid.PERMISSIONS.READ_SMS,
        {
          title: 'SMS Permission',
          message: 'MyExpenseTracker needs access to your SMS to track expenses automatically.',
          buttonNeutral: 'Ask Me Later',
          buttonNegative: 'Cancel',
          buttonPositive: 'OK',
        }
      );

      if (granted !== PermissionsAndroid.RESULTS.GRANTED) {
        console.log('SMS permission denied');
        return false;
      }

      console.log('Fetching SMS...');
      const messages: SmsMessage[] = await SmsModule.getRecentSms(limit);
      
      console.log(`Found ${messages.length} messages.`);
      
      if (messages.length === 0) return true;

      return await this.sendToBackend(messages);
    } catch (error) {
      console.error('Error syncing SMS:', error);
      return false;
    }
  }

  private async sendToBackend(messages: SmsMessage[]): Promise<boolean> {
    try {
      const accessToken = await AsyncStorage.getItem('accessToken');
      if (!accessToken) {
        console.error('No access token found for SMS sync');
        return false;
      }

      console.log(`Sending ${messages.length} messages to Gateway...`);
      // Endpoint specified by user: POST /sms/v1/ingest
      const response = await fetch(`${API_BASE_URL}/sms/v1/ingest`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${accessToken}`,
        },
        body: JSON.stringify({ messages }),
      });

      if (!response.ok) {
        console.error('Failed to ingest SMS, status:', response.status);
        return false;
      }

      console.log('SMS messages successfully ingested');
      return true;
    } catch (error) {
      console.error('Network error while sending SMS to backend:', error);
      return false;
    }
  }
}

export default new SmsService();
