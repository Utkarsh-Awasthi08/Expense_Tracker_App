import AsyncStorage from '@react-native-async-storage/async-storage';
import { API_BASE_URL } from './config';

class ManualExpenseService {
  async submitExpense(text: string): Promise<boolean> {
    try {
      const token = await AsyncStorage.getItem('userToken');
      if (!token) {
        console.error('No token found');
        return false;
      }

      const response = await fetch(`${API_BASE_URL}/manual/v1/manual`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${token}`,
        },
        body: JSON.stringify({ text }),
      });

      if (!response.ok) {
        const errorText = await response.text();
        console.error('Failed to submit manual expense', errorText);
        return false;
      }

      return true;
    } catch (error) {
      console.error('Error submitting manual expense:', error);
      return false;
    }
  }
}

export default new ManualExpenseService();
