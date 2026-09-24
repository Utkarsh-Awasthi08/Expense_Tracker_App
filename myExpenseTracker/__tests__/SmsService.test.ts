import { PermissionsAndroid, Platform, NativeModules } from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { API_BASE_URL } from '../src/app/api/config';

// Mock react-native NativeModules
jest.mock('react-native', () => {
  const RN = jest.requireActual('react-native');
  RN.NativeModules.SmsModule = {
    getRecentSms: jest.fn(),
  };
  return RN;
});

import SmsService from '../src/app/api/SmsService';

// Mock AsyncStorage
jest.mock('@react-native-async-storage/async-storage', () => ({
  getItem: jest.fn(),
}));

// Mock fetch
global.fetch = jest.fn() as jest.Mock;

describe('SmsService', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('should return false if platform is not android', async () => {
    Platform.OS = 'ios';
    const result = await SmsService.syncSmsMessages(50);
    expect(result).toBe(false);
  });

  it('should return false if permission is denied', async () => {
    Platform.OS = 'android';
    jest.spyOn(PermissionsAndroid, 'request').mockResolvedValueOnce(PermissionsAndroid.RESULTS.DENIED);
    const result = await SmsService.syncSmsMessages(50);
    expect(result).toBe(false);
  });

  it('should return true if there are no messages', async () => {
    Platform.OS = 'android';
    jest.spyOn(PermissionsAndroid, 'request').mockResolvedValueOnce(PermissionsAndroid.RESULTS.GRANTED);
    NativeModules.SmsModule.getRecentSms.mockResolvedValueOnce([]);

    const result = await SmsService.syncSmsMessages(50);
    expect(result).toBe(true);
  });

  it('should ingest messages if permission granted and messages found', async () => {
    Platform.OS = 'android';
    jest.spyOn(PermissionsAndroid, 'request').mockResolvedValueOnce(PermissionsAndroid.RESULTS.GRANTED);
    NativeModules.SmsModule.getRecentSms.mockResolvedValueOnce([
      { id: '1', address: '123', body: 'Test', date: '100' }
    ]);
    (AsyncStorage.getItem as jest.Mock).mockResolvedValueOnce('mock_token');
    (global.fetch as jest.Mock).mockResolvedValueOnce({ ok: true, status: 200 });

    const result = await SmsService.syncSmsMessages(50);
    expect(result).toBe(true);
    expect(global.fetch).toHaveBeenCalledWith(`${API_BASE_URL}/sms/v1/ingest`, expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ messages: [{ id: '1', address: '123', body: 'Test', date: '100' }] })
    }));
  });
});
