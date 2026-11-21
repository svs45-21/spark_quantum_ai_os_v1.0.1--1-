/* Program 9(d) : Radix Sort Method
                  (for non-negative integers, max 32767 in Turbo C)
   Compiler     : Turbo C++ 3.0                                    */
#include<iostream.h>
#include<conio.h>
#include<stdio.h>
#define MAX 100

int bucket[10][MAX];     /* global to keep Turbo C stack small */

void radix_sort(int arr[], int n);

void main()
{
  int array[MAX], n, i;
  clrscr();
  cout<<"Enter the number of elements to be sorted (max "<<MAX<<"): ";
  cin>>n;
  if(n>MAX) n=MAX;
  cout<<"\nEnter the elements to be sorted: \n";
  for(i=0;i<n;i++)
  {
    cin>>array[i];
  }
  cout<<"\nArray Before Radix Sort:\n";
  for(i=0;i<n;i++)
  {
    cout<<array[i]<<"\t";
  }
  cout<<"\n";
  radix_sort(array,n);
  cout<<"\nArray After Radix Sort:\n";
  for(i=0;i<n;i++)
  {
    cout<<array[i]<<"\t";
  }
  cout<<"\n";
  getch();
}

void radix_sort(int arr[], int n)
{
  int buck[10];
  int i, j, k, l, num, div, large, passes;
  if(n<=0)
    return;
  div=1;
  num=0;
  large=arr[0];
  for(i=0;i<n;i++)          /* find the largest number */
  {
    if(arr[i]>large)
      large=arr[i];
  }
  while(large>0)            /* count number of digits = passes */
  {
    num++;
    large=large/10;
  }
  for(passes=0;passes<num;passes++)
  {
    for(k=0;k<10;k++)
      buck[k]=0;
    for(i=0;i<n;i++)        /* put numbers into buckets by digit */
    {
      l=((arr[i]/div)%10);
      bucket[l][buck[l]++]=arr[i];
    }
    i=0;
    for(k=0;k<10;k++)       /* collect numbers back from buckets */
    {
      for(j=0;j<buck[k];j++)
        arr[i++]=bucket[k][j];
    }
    div*=10;
  }
}
