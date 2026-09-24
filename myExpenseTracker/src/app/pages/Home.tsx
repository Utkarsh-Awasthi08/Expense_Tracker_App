import React, { useState, useEffect } from 'react';
import {SafeAreaView, StyleSheet, Text, View, ScrollView} from 'react-native';
import {GluestackUIProvider} from '@gluestack-ui/themed';
import {config} from '@gluestack-ui/config';
import ExpenseTrackerGraph from './ExpenseTrackerGraph';
import SpendsInsights from './SpendsInsights';
import Spends from './Spends';
import Nav from './Nav';
import ExpenseService from '../api/ExpenseService';
import UserService from '../api/UserService';
import { ExpenseDto } from './dto/ExpenseDto';

const Home = () => {
  const [expenses, setExpenses] = useState<ExpenseDto[]>([]);
  const [isLoading, setIsLoading] = useState<boolean>(true);
  const [budget, setBudget] = useState<number | null>(null);
  const [spent, setSpent] = useState<number>(0);
  
  const [filterType, setFilterType] = useState<'7days' | 'month' | 'all'>('month');
  const [sortOrder, setSortOrder] = useState<'date' | 'price'>('date');

  const fetchExpenses = async () => {
    setIsLoading(true);
    try {
      let fromDate: string | undefined;
      let toDate: string | undefined;

      const now = new Date();
      if (filterType === '7days') {
        const sevenDaysAgo = new Date(now.setDate(now.getDate() - 7));
        fromDate = sevenDaysAgo.toISOString().split('T')[0];
      } else if (filterType === 'month') {
        const firstDay = new Date(now.getFullYear(), now.getMonth(), 1);
        fromDate = firstDay.toISOString().split('T')[0];
      }

      const data = await ExpenseService.getExpenses(fromDate, toDate);
      
      let transformedExpenses: ExpenseDto[] = data.map((expense: any, index: number) => ({
        key: index + 1,
        amount: expense.amount,
        merchant: expense.merchant,
        currency: expense.currency,
        createdAt: new Date(expense.txn_date || expense.created_at),
        category: expense.category,
      }));

      if (sortOrder === 'price') {
        transformedExpenses.sort((a, b) => b.amount - a.amount);
      }

      setExpenses(transformedExpenses);
      
      const profile = await UserService.getUserProfile();
      if (profile && profile.monthly_budget) {
        setBudget(profile.monthly_budget);
      }
      
      const totalSpent = await ExpenseService.getCurrentMonthTotal();
      setSpent(totalSpent);

    } catch (err) {
      console.error('Error fetching dashboard data:', err);
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    fetchExpenses();
  }, [filterType, sortOrder]);

  return (
    <GluestackUIProvider config={config}>
      <SafeAreaView style={styles.safeArea}>
        <View style={styles.container}>
          <Nav />
          <ScrollView showsVerticalScrollIndicator={false}>
            <View style={styles.contentContainer}>
              <View style={styles.graphContainer}>
                <ExpenseTrackerGraph expenses={expenses} />
              </View>
              <View style={styles.insightsContainer}>
                <SpendsInsights expenses={expenses} budget={budget} spent={spent} />
              </View>
            </View>
            <View style={styles.spendsContainer}>
              <Spends 
                expenses={expenses} 
                isLoading={isLoading} 
                budget={budget} 
                spent={spent}
                filterType={filterType}
                setFilterType={setFilterType}
                sortOrder={sortOrder}
                setSortOrder={setSortOrder}
                onRefresh={fetchExpenses}
              />
            </View>
          </ScrollView>
        </View>
      </SafeAreaView>
    </GluestackUIProvider>
  );
};

const styles = StyleSheet.create({
  safeArea: {
    flex: 1,
    backgroundColor: 'white',
  },
  container: {
    flex: 1,
    paddingHorizontal: 16,
    flexDirection: 'column',
  },
  contentContainer: {
    flexDirection: 'column', // Changed to column so graph takes full width
    alignItems: 'stretch',
    marginTop: 20,
  },
  graphContainer: {
    marginBottom: 20,
    alignItems: 'center',
  },
  insightsContainer: {
    marginBottom: 10,
  },
  spendsContainer: {
    marginTop: 10,
    flex: 1,
  },
});

export default Home;