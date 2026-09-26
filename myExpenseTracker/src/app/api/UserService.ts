import AsyncStorage from '@react-native-async-storage/async-storage';
import { API_BASE_URL } from './config';

export interface UserInfoDTO {
    user_id: string;
    first_name: string | null;
    last_name: string | null;
    phone_number: string | null;
    email: string | null;
    profile_picture: string | null;
    default_currency: string | null;
    timezone: string | null;
    monthly_budget?: number | null;
    current_streak?: number;
}

export interface UpdateProfileRequest {
    first_name?: string;
    last_name?: string;
    phone_number?: string;
    email?: string;
    profile_picture?: string;
    default_currency?: string;
    timezone?: string;
    monthly_budget?: number;
}

class UserService {
    private async getHeaders(): Promise<Record<string, string>> {
        const accessToken = await AsyncStorage.getItem('accessToken');
        return {
            Accept: 'application/json',
            'Content-Type': 'application/json',
            Authorization: 'Bearer ' + accessToken,
        };
    }

    async getUserProfile(retries = 3): Promise<UserInfoDTO | null> {
        try {
            const response = await fetch(`${API_BASE_URL}/user/v1/me`, {
                method: 'GET',
                headers: await this.getHeaders(),
            });
            if (response.status === 404 && retries > 0) {
                await new Promise(resolve => setTimeout(resolve, 1000));
                return this.getUserProfile(retries - 1);
            }
            if (!response.ok) {
                if (response.status !== 404 && response.status !== 401) {
                    console.error('Failed to fetch user profile, status:', response.status);
                }
                return null;
            }
            return await response.json();
        } catch (error) {
            console.error('Error fetching user profile:', error);
            return null;
        }
    }

    async updateUserProfile(data: UpdateProfileRequest): Promise<boolean> {
        try {
            const response = await fetch(`${API_BASE_URL}/user/v1/me`, {
                method: 'PUT',
                headers: await this.getHeaders(),
                body: JSON.stringify(data),
            });
            if (!response.ok) {
                console.error('Failed to update user profile, status:', response.status);
                return false;
            }
            return true;
        } catch (error) {
            console.error('Error updating user profile:', error);
            return false;
        }
    }
}

export default new UserService();
