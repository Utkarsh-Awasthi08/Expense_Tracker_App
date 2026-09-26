import React, { useMemo } from 'react';
import { View, Dimensions } from 'react-native';
import { PieChart } from 'react-native-chart-kit';
import { ExpenseDto } from './dto/ExpenseDto';
import CustomText from '../components/CustomText';

interface ExpenseTrackerGraphProps {
  expenses: ExpenseDto[];
}

const screenWidth = Dimensions.get('window').width - 32;

const categoryColors: Record<string, string> = {
  'GROCERIES': '#FF6384',
  'DINING': '#36A2EB',
  'TRANSPORT': '#FFCE56',
  'SHOPPING': '#4BC0C0',
  'ENTERTAINMENT': '#9966FF',
  'BILLS': '#FF9F40',
  'HEALTH': '#C9CBCF',
  'OTHER': '#A9A9A9',
};

const ExpenseTrackerGraph: React.FC<ExpenseTrackerGraphProps> = ({ expenses }) => {
  const chartData = useMemo(() => {
    if (!expenses || expenses.length === 0) return [];
    
    const categoryTotals: Record<string, number> = {};
    expenses.forEach(exp => {
      const cat = exp.category || 'OTHER';
      categoryTotals[cat] = (categoryTotals[cat] || 0) + exp.amount;
    });

    return Object.entries(categoryTotals).map(([cat, total]) => ({
      name: cat,
      amount: total,
      color: categoryColors[cat] || categoryColors['OTHER'],
      legendFontColor: '#94a3b8',
      legendFontSize: 12
    })).sort((a, b) => b.amount - a.amount);
  }, [expenses]);

  return (
    <View style={{ alignItems: 'center', marginTop: 10, backgroundColor: '#0f172a' }}>
      {chartData.length > 0 ? (
        <PieChart
          data={chartData}
          width={screenWidth}
          height={200}
          chartConfig={{
            color: (opacity = 1) => `rgba(255,255,255, ${opacity})`,
            backgroundColor: '#0f172a',
          }}
          accessor={"amount"}
          backgroundColor={"transparent"}
          paddingLeft={"15"}
          center={[0, 0]}
          absolute
        />
      ) : (
        <View style={{ paddingVertical: 40, alignItems: 'center' }}>
          <CustomText style={{ color: '#334155', fontSize: 14, fontWeight: '500' }}>No spending data yet</CustomText>
          <CustomText style={{ color: '#1e293b', fontSize: 12, marginTop: 4 }}>Add expenses to see your breakdown</CustomText>
        </View>
      )}
    </View>
  );
};

export default ExpenseTrackerGraph;