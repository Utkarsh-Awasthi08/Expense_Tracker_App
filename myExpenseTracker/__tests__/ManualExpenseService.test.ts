import AsyncStorage from '@react-native-async-storage/async-storage';
import ManualExpenseService from '../src/app/api/ManualExpenseService';
import { API_BASE_URL } from '../src/app/api/config';

jest.mock('@react-native-async-storage/async-storage', () => ({
  getItem: jest.fn(),
}));

global.fetch = jest.fn() as jest.Mock;

describe('ManualExpenseService', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('should return false if token is not found', async () => {
    (AsyncStorage.getItem as jest.Mock).mockResolvedValueOnce(null);
    const result = await ManualExpenseService.submitExpense('Spent 10 on food');
    expect(result).toBe(false);
  });

  it('should submit successfully if token is valid', async () => {
    (AsyncStorage.getItem as jest.Mock).mockResolvedValueOnce('mock_token');
    (global.fetch as jest.Mock).mockResolvedValueOnce({ ok: true, status: 200 });

    const result = await ManualExpenseService.submitExpense('Spent 10 on food');
    
    expect(result).toBe(true);
    expect(global.fetch).toHaveBeenCalledWith(`${API_BASE_URL}/manual/v1/manual`, expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ text: 'Spent 10 on food' })
    }));
  });
});
