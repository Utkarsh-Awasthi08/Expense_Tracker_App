import ExpenseService from '../src/app/api/ExpenseService';
import AsyncStorage from '@react-native-async-storage/async-storage';

// Mock AsyncStorage and fetch
jest.mock('@react-native-async-storage/async-storage', () => ({
  getItem: jest.fn(),
}));

global.fetch = jest.fn();

describe('ExpenseService', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('getCurrentMonthTotal returns amount on success', async () => {
    (AsyncStorage.getItem as jest.Mock).mockResolvedValue('fake_token');
    
    (global.fetch as jest.Mock).mockResolvedValue({
      ok: true,
      json: jest.fn().mockResolvedValue(1234.56),
    });

    const total = await ExpenseService.getCurrentMonthTotal();
    
    expect(total).toBe(1234.56);
    expect(global.fetch).toHaveBeenCalledWith(
      expect.stringContaining('/expense/v1/currentMonthTotal'),
      expect.objectContaining({
        method: 'GET',
        headers: {
          Accept: 'application/json',
          'Content-Type': 'application/json',
          Authorization: 'Bearer fake_token',
        },
      })
    );
  });

  it('getCurrentMonthTotal returns 0 on failure', async () => {
    (AsyncStorage.getItem as jest.Mock).mockResolvedValue('fake_token');
    
    (global.fetch as jest.Mock).mockResolvedValue({
      ok: false,
      status: 500,
    });

    const total = await ExpenseService.getCurrentMonthTotal();
    
    expect(total).toBe(0);
  });
});
