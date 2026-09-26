import AsyncStorage from '@react-native-async-storage/async-storage';
import { API_BASE_URL } from './config';

class ExpenseService {
    private async getHeaders(): Promise<Record<string, string>> {
        const accessToken = await AsyncStorage.getItem('accessToken');
        return {
            Accept: 'application/json',
            'Content-Type': 'application/json',
            Authorization: 'Bearer ' + accessToken,
        };
    }

    async getCurrentMonthTotal(): Promise<number> {
        try {
            const response = await fetch(`${API_BASE_URL}/expense/v1/currentMonthTotal`, {
                method: 'GET',
                headers: await this.getHeaders(),
            });
            if (!response.ok) {
                console.error('Failed to fetch current month total, status:', response.status);
                return 0;
            }
            const data = await response.json();
            return typeof data === 'number' ? data : 0;
        } catch (error) {
            console.error('Error fetching current month total:', error);
            return 0;
        }
    }

    async getExpenses(from?: string, to?: string): Promise<any[]> {
        try {
            let url = `${API_BASE_URL}/expense/v1/expenses?size=200`;
            if (from) url += `&from=${from}`;
            if (to) url += `&to=${to}`;
            
            const response = await fetch(url, {
                method: 'GET',
                headers: await this.getHeaders(),
            });
            if (!response.ok) {
                console.error('Failed to fetch expenses, status:', response.status);
                return [];
            }
            const data = await response.json();
            return data.items || [];
        } catch (error) {
            console.error('Error fetching expenses:', error);
            return [];
        }
    }

    async createMerchantAlias(originalName: string, aliasName: string): Promise<boolean> {
        try {
            const response = await fetch(`${API_BASE_URL}/expense/v1/merchant-alias`, {
                method: 'POST',
                headers: await this.getHeaders(),
                body: JSON.stringify({
                    original_name: originalName,
                    alias_name: aliasName
                }),
            });
            return response.ok;
        } catch (error) {
            console.error('Error creating merchant alias:', error);
            return false;
        }
    }
}

export default new ExpenseService();
