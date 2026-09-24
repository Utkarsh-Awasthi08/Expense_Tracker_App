import AsyncStorage from '@react-native-async-storage/async-storage';
import { API_BASE_URL } from './config';

export interface Subscription {
    id?: number;
    platform: string;
    amount: number;
    billingDay: number;
    currency?: string;
}

class SubscriptionService {
    private async getHeaders(): Promise<HeadersInit> {
        const accessToken = await AsyncStorage.getItem('accessToken');
        return {
            Accept: 'application/json',
            'Content-Type': 'application/json',
            Authorization: 'Bearer ' + accessToken,
        };
    }

    async getSubscriptions(): Promise<Subscription[]> {
        try {
            const response = await fetch(`${API_BASE_URL}/subscription/v1`, {
                method: 'GET',
                headers: await this.getHeaders(),
            });
            if (!response.ok) return [];
            return await response.json();
        } catch (error) {
            console.error('Error fetching subscriptions:', error);
            return [];
        }
    }

    async createSubscription(sub: Subscription): Promise<Subscription | null> {
        try {
            const response = await fetch(`${API_BASE_URL}/subscription/v1`, {
                method: 'POST',
                headers: await this.getHeaders(),
                body: JSON.stringify(sub),
            });
            if (!response.ok) return null;
            return await response.json();
        } catch (error) {
            console.error('Error creating subscription:', error);
            return null;
        }
    }

    async deleteSubscription(id: number): Promise<boolean> {
        try {
            const response = await fetch(`${API_BASE_URL}/subscription/v1/${id}`, {
                method: 'DELETE',
                headers: await this.getHeaders(),
            });
            return response.ok;
        } catch (error) {
            console.error('Error deleting subscription:', error);
            return false;
        }
    }
}

export default new SubscriptionService();
